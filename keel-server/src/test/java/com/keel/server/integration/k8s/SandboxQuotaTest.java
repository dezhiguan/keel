package com.keel.server.integration.k8s;

import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.ResourceQuotaBuilder;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class SandboxQuotaTest {

    @Test void formatsCpuAndMemoryLikeTheConsolePrototype() {
        var used = Map.of(
                "requests.cpu", new Quantity("1500m"),
                "requests.memory", new Quantity("3Gi"),
                "pods", new Quantity("2"));
        var hard = Map.of(
                "requests.cpu", new Quantity("4"),
                "requests.memory", new Quantity("8Gi"),
                "pods", new Quantity("4"));
        assertThat(SandboxQuota.usageOf(used, hard, 2, 0)).isEqualTo("1.5 / 4 核 · 3 / 8Gi · 运行 2 · 排队 0");
        assertThat(SandboxQuota.statusOf(used, hard, 0)).isEqualTo("ONLINE");
        assertThat(SandboxQuota.statusOf(used, hard, 1)).isEqualTo("DEGRADED");
    }

    @Test void fullQuotaIsDegraded() {
        var used = Map.of("requests.cpu", new Quantity("4"), "requests.memory", new Quantity("8Gi"), "pods", new Quantity("4"));
        var hard = Map.of("requests.cpu", new Quantity("4"), "requests.memory", new Quantity("8Gi"), "pods", new Quantity("4"));
        assertThat(SandboxQuota.statusOf(used, hard, 0)).isEqualTo("DEGRADED");
    }

    @Test void outsideTheClusterDoesNotInventUsage() throws Exception {
        var server = new KubernetesMockServer();
        server.start();
        try (var client = server.createClient()) {
            assertThat(SandboxQuota.read(client, 2, 0, false)).isNull();
        } finally {
            server.destroy();
        }
    }

    @Test void readsQuotaAndKeepsQueueCounts() throws Exception {
        var server = new KubernetesMockServer();
        server.start();
        server.expect().get()
                .withPath("/api/v1/namespaces/keel-devflow-sandbox/resourcequotas/sandbox")
                .andReturn(200, quota("1500m", "3Gi", "2"))
                .once();
        try (var client = server.createClient()) {
            assertThat(SandboxQuota.read(client, 2, 0, true))
                    .isEqualTo(new SandboxQuota.Reading("ONLINE", "1.5 / 4 核 · 3 / 8Gi · 运行 2 · 排队 0"));
        } finally {
            server.destroy();
        }
    }

    @Test void missingQuotaIsOfflineWithoutAFakeUsage() throws Exception {
        var server = new KubernetesMockServer();
        server.start();
        server.expect().get()
                .withPath("/api/v1/namespaces/keel-devflow-sandbox/resourcequotas/sandbox")
                .andReturn(404, "")
                .once();
        try (var client = server.createClient()) {
            assertThat(SandboxQuota.read(client, 0, 0, true))
                    .isEqualTo(new SandboxQuota.Reading("OFFLINE", null));
        } finally {
            server.destroy();
        }
    }

    private static io.fabric8.kubernetes.api.model.ResourceQuota quota(String cpu, String memory, String pods) {
        return new ResourceQuotaBuilder()
                .withNewMetadata().withName("sandbox").withNamespace("keel-devflow-sandbox").endMetadata()
                .withNewSpec()
                    .addToHard("requests.cpu", new Quantity("4"))
                    .addToHard("requests.memory", new Quantity("8Gi"))
                    .addToHard("pods", new Quantity("4"))
                .endSpec()
                .withNewStatus()
                    .addToHard("requests.cpu", new Quantity("4"))
                    .addToHard("requests.memory", new Quantity("8Gi"))
                    .addToHard("pods", new Quantity("4"))
                    .addToUsed("requests.cpu", new Quantity(cpu))
                    .addToUsed("requests.memory", new Quantity(memory))
                    .addToUsed("pods", new Quantity(pods))
                .endStatus()
                .build();
    }
}
