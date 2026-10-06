package com.keel.server.integration.k8s;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceHealthProbeTest {

    @Test void replicaRatioMapsToStatus() {
        assertThat(ServiceHealthProbe.statusOf("2/2")).isEqualTo("ONLINE");
        assertThat(ServiceHealthProbe.statusOf("1/2")).isEqualTo("DEGRADED");
        assertThat(ServiceHealthProbe.statusOf("0/2")).isEqualTo("OFFLINE");
    }

    @Test void outsideTheClusterDoesNotDial() {
        var result = new ServiceHealthProbe(null).cluster("keel-system", "keel-gateway", 8080, "/", 500);
        assertThat(result.status()).isNull();
        assertThat(result.instances()).isNull();
    }
}
