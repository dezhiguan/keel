package com.keel.server.devflow;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** One Job in keel-devflow-sandbox. The pod has no Secret and no ServiceAccount token. */
@Component
public class Fabric8SandboxCluster implements SandboxCluster {
    static final String NAMESPACE = "keel-devflow-sandbox";

    private final KubernetesClient client;
    private final String image;
    private final GitSnapshotClient snapshots;

    @Autowired
    public Fabric8SandboxCluster(KubernetesClient client, GitSnapshotClient snapshots,
                                 @Value("${keel.sandbox.image:keel-sandbox:local}") String image) {
        this.client = client;
        this.snapshots = snapshots;
        this.image = image;
    }

    Fabric8SandboxCluster(KubernetesClient client, String image) {
        this(client, null, image);
    }

    @Override
    public void submit(SandboxRecord run) {
        if (image == null || image.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "未配置沙箱镜像");
        }
        var name = SandboxRules.jobName(run.runId());
        var source = name + "-source";
        if (snapshots == null) {
            throw new KeelException(ErrorCode.GIT_UPSTREAM_FAILED, "Git 快照服务未配置");
        }
        var bundle = snapshots.fetch(run.jobId(), run.repo(), run.ref());
        var config = new ConfigMapBuilder().withNewMetadata().withName(source).withNamespace(NAMESPACE)
                .endMetadata().addToData("bundle.json", bundle).build();
        var job = new JobBuilder()
                .withNewMetadata().withName(name).withNamespace(NAMESPACE).addToLabels("app", "keel-sandbox").endMetadata()
                .withNewSpec()
                    .withBackoffLimit(0)
                    .withActiveDeadlineSeconds(600L)
                    .withTtlSecondsAfterFinished(600)
                    .withNewTemplate()
                        .withNewMetadata()
                            .addToLabels("app", "keel-sandbox")
                            .addToLabels("keel.io/sandbox-run", name)
                        .endMetadata()
                        .withNewSpec()
                            .withAutomountServiceAccountToken(false)
                            .withEnableServiceLinks(false)
                            .withImagePullSecrets(new io.fabric8.kubernetes.api.model.LocalObjectReferenceBuilder()
                                    .withName("acr-cred").build())
                            .withNewSecurityContext().withFsGroup(65534L).endSecurityContext()
                            .withRestartPolicy("Never")
                            .addNewVolume().withName("tmp").withNewEmptyDir().endEmptyDir().endVolume()
                            .addNewVolume().withName("work").withNewEmptyDir().endEmptyDir().endVolume()
                            .addNewVolume().withName("source").withNewConfigMap().withName(source)
                                .endConfigMap().endVolume()
                            .addToContainers(container("fake-llm", "python", "/app/fake_llm.py", "50m", "64Mi", "250m", "512Mi"))
                            .addToContainers(container("fake-langfuse", "python", "/app/fake_langfuse.py", "50m", "64Mi", "250m", "512Mi"))
                            .addToContainers(runner(run))
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
        try {
            client.configMaps().inNamespace(NAMESPACE).resource(config).create();
            client.batch().v1().jobs().inNamespace(NAMESPACE).resource(job).create();
        } catch (KubernetesClientException e) {
            client.configMaps().inNamespace(NAMESPACE).withName(source).delete();
            throw failed(e);
        }
    }

    @Override
    public Observation observe(String runId) {
        var name = SandboxRules.jobName(runId);
        Job job;
        try {
            job = client.batch().v1().jobs().inNamespace(NAMESPACE).withName(name).get();
        } catch (KubernetesClientException e) {
            if (e.getCode() == 404) {
                return new Observation(Phase.MISSING, "");
            }
            throw failed(e);
        }
        if (job == null || job.getStatus() == null) {
            return new Observation(Phase.PENDING, "");
        }
        var runner = runnerResult(name);
        if (runner != null) return runner;
        var status = job.getStatus();
        if (status.getConditions() != null) {
            for (var condition : status.getConditions()) {
                if ("Failed".equals(condition.getType()) && "DeadlineExceeded".equals(condition.getReason())) {
                    return new Observation(Phase.DEADLINE, logs(name));
                }
            }
        }
        if (status.getSucceeded() != null && status.getSucceeded() > 0) {
            return new Observation(Phase.SUCCEEDED, logs(name));
        }
        if (status.getFailed() != null && status.getFailed() > 0) {
            return new Observation(Phase.FAILED, logs(name));
        }
        if (status.getActive() != null && status.getActive() > 0) {
            return new Observation(Phase.RUNNING, diagnosis(name));
        }
        return new Observation(Phase.PENDING, diagnosis(name));
    }

    private Observation runnerResult(String name) {
        try {
            var pods = client.pods().inNamespace(NAMESPACE).withLabel("keel.io/sandbox-run", name).list().getItems();
            if (pods == null) return null;
            for (var pod : pods) {
                var phase = runnerPhase(pod);
                if (phase != null) return new Observation(phase, logs(name));
            }
        } catch (KubernetesClientException e) {
            return null;
        }
        return null;
    }

    static Phase runnerPhase(Pod pod) {
        if (pod == null || pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) return null;
        for (var container : pod.getStatus().getContainerStatuses()) {
            if (!"runner".equals(container.getName()) || container.getState() == null
                    || container.getState().getTerminated() == null) continue;
            var exitCode = container.getState().getTerminated().getExitCode();
            if (exitCode != null) return exitCode == 0 ? Phase.SUCCEEDED : Phase.FAILED;
        }
        return null;
    }

    @Override
    public void delete(String runId) {
        try {
            client.batch().v1().jobs().inNamespace(NAMESPACE).withName(SandboxRules.jobName(runId)).delete();
            client.configMaps().inNamespace(NAMESPACE).withName(SandboxRules.jobName(runId) + "-source").delete();
        } catch (KubernetesClientException e) {
            if (e.getCode() != 404) {
                throw failed(e);
            }
        }
    }

    private Container runner(SandboxRecord run) {
        return new ContainerBuilder(container("runner", "python", "/app/runner.py", "100m", "256Mi", "500m", "1Gi"))
                .addNewEnv().withName("KEEL_SANDBOX_REPO").withValue(run.repo()).endEnv()
                .addNewEnv().withName("KEEL_SANDBOX_REF").withValue(run.ref()).endEnv()
                .addNewEnv().withName("KEEL_LLM_BASE_URL").withValue("http://127.0.0.1:8088").endEnv()
                .addNewEnv().withName("LANGFUSE_HOST").withValue("http://127.0.0.1:3000").endEnv()
                .addNewVolumeMount().withName("work").withMountPath("/work").endVolumeMount()
                .addNewVolumeMount().withName("source").withMountPath("/source").withReadOnly(true).endVolumeMount()
                .build();
    }

    private Container container(String name, String command, String script, String requestCpu, String requestMemory,
                                String limitCpu, String limitMemory) {
        return new ContainerBuilder()
                .withName(name)
                .withImage(image)
                .withCommand(command, script)
                .addNewEnv().withName("PYTHONDONTWRITEBYTECODE").withValue("1").endEnv()
                .addNewEnv().withName("HOME").withValue("/tmp").endEnv()
                .addNewEnv().withName("TMPDIR").withValue("/tmp").endEnv()
                .withNewSecurityContext()
                    .withRunAsNonRoot(true)
                    .withRunAsUser(65534L)
                    .withReadOnlyRootFilesystem(true)
                    .withAllowPrivilegeEscalation(false)
                    .withNewCapabilities().withDrop("ALL").endCapabilities()
                .endSecurityContext()
                .withNewResources()
                    .addToRequests("cpu", new Quantity(requestCpu))
                    .addToRequests("memory", new Quantity(requestMemory))
                    .addToLimits("cpu", new Quantity(limitCpu))
                    .addToLimits("memory", new Quantity(limitMemory))
                .endResources()
                .addNewVolumeMount().withName("tmp").withMountPath("/tmp").endVolumeMount()
                .build();
    }

    private String logs(String name) {
        try {
            var pods = client.pods().inNamespace(NAMESPACE).withLabel("keel.io/sandbox-run", name).list().getItems();
            if (pods == null || pods.isEmpty()) {
                return "";
            }
            var pod = pods.get(0).getMetadata().getName();
            var log = client.pods().inNamespace(NAMESPACE).withName(pod).inContainer("runner").getLog();
            return log == null || log.isBlank() ? podDiagnosis(pods.get(0)) : log;
        } catch (KubernetesClientException e) {
            return diagnosis(name);
        }
    }

    private String diagnosis(String name) {
        try {
            var pods = client.pods().inNamespace(NAMESPACE).withLabel("keel.io/sandbox-run", name).list().getItems();
            return pods == null || pods.isEmpty() ? "Pod 尚未创建" : podDiagnosis(pods.get(0));
        } catch (KubernetesClientException e) {
            return "Pod 状态暂不可读";
        }
    }

    static String podDiagnosis(Pod pod) {
        if (pod == null || pod.getStatus() == null) return "Pod 尚无状态";
        var status = pod.getStatus();
        if (status.getContainerStatuses() != null) {
            String genericWait = null;
            for (var container : status.getContainerStatuses()) {
                var waiting = container.getState() == null ? null : container.getState().getWaiting();
                if (waiting != null && waiting.getReason() != null && !waiting.getReason().isBlank()) {
                    var detail = compact("容器 " + container.getName() + " 等待：" + waiting.getReason()
                            + " " + (waiting.getMessage() == null ? "" : waiting.getMessage()));
                    if (!"ContainerCreating".equals(waiting.getReason())
                            && !"PodInitializing".equals(waiting.getReason())) return detail;
                    if (genericWait == null) genericWait = detail;
                }
            }
            if (genericWait != null) return genericWait;
        }
        if (status.getConditions() != null) {
            for (var condition : status.getConditions()) {
                if (condition.getReason() != null && !condition.getReason().isBlank()) {
                    return compact("Pod " + condition.getType() + "：" + condition.getReason()
                            + " " + (condition.getMessage() == null ? "" : condition.getMessage()));
                }
            }
        }
        return compact("Pod 阶段：" + (status.getPhase() == null ? "未知" : status.getPhase()));
    }

    private static String compact(String text) {
        var singleLine = text.replace('\n', ' ').replace('\r', ' ').trim();
        return singleLine.length() <= 500 ? singleLine : singleLine.substring(0, 500);
    }

    private static KeelException failed(KubernetesClientException e) {
        return new KeelException(ErrorCode.SERVER_INTERNAL_ERROR, "沙箱 Job 创建或读取失败");
    }
}
