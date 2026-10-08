package com.keel.server.devflow;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** One Job in keel-devflow-sandbox. The pod has no Secret and no ServiceAccount token. */
@Component
public class Fabric8SandboxCluster implements SandboxCluster {
    static final String NAMESPACE = "keel-devflow-sandbox";

    private final KubernetesClient client;
    private final String image;

    public Fabric8SandboxCluster(KubernetesClient client,
                                 @Value("${keel.sandbox.image:keel-sandbox:local}") String image) {
        this.client = client;
        this.image = image;
    }

    @Override
    public void submit(SandboxRecord run) {
        if (image == null || image.isBlank()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "未配置沙箱镜像");
        }
        var name = SandboxRules.jobName(run.runId());
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
                            .withRestartPolicy("Never")
                            .addNewVolume().withName("tmp").withNewEmptyDir().endEmptyDir().endVolume()
                            .addNewVolume().withName("work").withNewEmptyDir().endEmptyDir().endVolume()
                            .addToContainers(container("fake-llm", "python", "/app/fake_llm.py", "50m", "64Mi", "250m", "512Mi"))
                            .addToContainers(container("fake-langfuse", "python", "/app/fake_langfuse.py", "50m", "64Mi", "250m", "512Mi"))
                            .addToContainers(runner(run))
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
        try {
            client.batch().v1().jobs().inNamespace(NAMESPACE).resource(job).create();
        } catch (KubernetesClientException e) {
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
            return new Observation(Phase.RUNNING, "");
        }
        return new Observation(Phase.PENDING, "");
    }

    @Override
    public void delete(String runId) {
        try {
            client.batch().v1().jobs().inNamespace(NAMESPACE).withName(SandboxRules.jobName(runId)).delete();
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
            return log == null ? "" : log;
        } catch (KubernetesClientException e) {
            return "";
        }
    }

    private static KeelException failed(KubernetesClientException e) {
        return new KeelException(ErrorCode.SERVER_INTERNAL_ERROR, "沙箱 Job 创建或读取失败");
    }
}
