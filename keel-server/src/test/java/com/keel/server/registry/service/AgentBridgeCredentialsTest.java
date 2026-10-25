package com.keel.server.registry.service;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class AgentBridgeCredentialsTest {
    @Test void credentialsStayScopedToTheirOwnAgent() {
        var credentials = new AgentBridgeCredentials("askdb-private", "meta-private", "career-private");
        assertThat(credentials.token("askdb")).isEqualTo("askdb-private");
        assertThat(credentials.token("meta-agent")).isEqualTo("meta-private");
        assertThat(credentials.token("careermate")).isEqualTo("career-private");
        assertThat(credentials.token("careermate-keel")).isEqualTo("career-private");
        assertThat(credentials.token("another-agent")).isNull();
    }
}
