package com.keel.server.registry.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CareerMateRegistrarTest {
    @Test void blankEndpointDoesNotRegister() {
        assertThat(CareerMateRegistrar.enabled(null)).isFalse();
        assertThat(CareerMateRegistrar.enabled("  ")).isFalse();
        assertThat(CareerMateRegistrar.enabled("http://careermate-backend.careermate.svc.cluster.local:18080")).isTrue();
    }

    @Test void manifestPointsAtTheConfiguredEndpoint() {
        var manifest = CareerMateRegistrar.manifest("http://careermate-backend.careermate.svc.cluster.local:18080");
        assertThat(manifest).contains("\"name\":\"careermate\"");
        assertThat(manifest).contains("\"language\":\"java\"");
        assertThat(manifest).contains("http://careermate-backend.careermate.svc.cluster.local:18080");
        assertThat(manifest).doesNotContain("sk-");
    }
}
