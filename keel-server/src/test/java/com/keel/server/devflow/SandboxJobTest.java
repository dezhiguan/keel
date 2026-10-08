package com.keel.server.devflow;

import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxJobTest {
    @Test
    void aSandboxJobHasNoSecretAndTalksOnlyToLocalFakes() throws Exception {
        var server = new KubernetesMockServer();
        server.start();
        var ack = new JobBuilder()
                .withNewMetadata().withName("sb-0001").withNamespace(Fabric8SandboxCluster.NAMESPACE).endMetadata()
                .withNewSpec()
                    .withNewTemplate()
                        .withNewSpec()
                            .withRestartPolicy("Never")
                            .addNewContainer().withName("runner").withImage("keel-sandbox:local").endContainer()
                        .endSpec()
                    .endTemplate()
                .endSpec()
                .build();
        server.expect().post()
                .withPath("/apis/batch/v1/namespaces/keel-devflow-sandbox/jobs")
                .andReturn(201, ack)
                .once();
        try (var client = server.createClient()) {
            var cluster = new Fabric8SandboxCluster(client, "keel-sandbox:local");
            var run = SandboxRecord.create("SB-0001", "DF-0001", "refund-explainer", "refs/heads/feature",
                    "RUNNING", "meta-agent", Instant.parse("2026-10-09T00:00:00Z"), Instant.parse("2026-10-09T00:10:00Z"));
            cluster.submit(run);
            var request = server.takeRequest(1, TimeUnit.SECONDS);
            var job = Serialization.unmarshal(request.getBody().readUtf8(), Job.class);
            assertJob(job);
        } finally {
            server.destroy();
        }
    }

    private static void assertJob(Job job) {
        assertThat(job.getMetadata().getNamespace()).isEqualTo(Fabric8SandboxCluster.NAMESPACE);
        assertThat(job.getMetadata().getName()).isEqualTo("sb-0001");
        assertThat(job.getSpec().getActiveDeadlineSeconds()).isEqualTo(600L);
        assertThat(job.getSpec().getBackoffLimit()).isZero();
        var pod = job.getSpec().getTemplate().getSpec();
        assertThat(pod.getAutomountServiceAccountToken()).isFalse();
        assertThat(pod.getEnableServiceLinks()).isFalse();
        assertThat(pod.getVolumes()).allSatisfy(volume -> {
            assertThat(volume.getSecret()).isNull();
            assertThat(volume.getEmptyDir()).isNotNull();
        });
        assertThat(pod.getContainers()).extracting(container -> container.getName())
                .containsExactly("fake-llm", "fake-langfuse", "runner");
        assertThat(pod.getContainers()).allSatisfy(container -> {
            assertThat(container.getSecurityContext().getReadOnlyRootFilesystem()).isTrue();
            assertThat(container.getSecurityContext().getAllowPrivilegeEscalation()).isFalse();
            assertThat(container.getEnvFrom()).isNullOrEmpty();
            assertThat(container.getEnv()).allSatisfy(env -> assertThat(env.getValueFrom()).isNull());
            assertThat(container.getEnv()).extracting(env -> env.getName())
                    .doesNotContain("KEEL_GITHUB_TOKEN", "QWEN_API_KEY", "LANGFUSE_PUBLIC_KEY", "LANGFUSE_SECRET_KEY");
        });
        var runner = pod.getContainers().get(2);
        assertThat(runner.getEnv()).anySatisfy(env -> {
            assertThat(env.getName()).isEqualTo("KEEL_LLM_BASE_URL");
            assertThat(env.getValue()).isEqualTo("http://127.0.0.1:8088");
        });
        assertThat(runner.getEnv()).anySatisfy(env -> {
            assertThat(env.getName()).isEqualTo("LANGFUSE_HOST");
            assertThat(env.getValue()).isEqualTo("http://127.0.0.1:3000");
        });
        assertThat(pod.getContainers()).allSatisfy(container ->
                assertThat(container.getImage()).isEqualTo("keel-sandbox:local"));
    }
}
