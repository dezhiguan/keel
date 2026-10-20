package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.common.KeelException;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RetiredEnvironmentTest {
    @Test void registerRejectsRetiredEnvironmentBeforeResolvingOrProvisioningResources() {
        var json = new ObjectMapper();
        var service = new LifecycleService(null, null, null, null, null, json, null, null, null, null, 8080);
        for (var env : java.util.List.of("dev", "test")) {
            assertThatThrownBy(() -> service.register(json.createObjectNode().put("env", env)))
                    .isInstanceOf(KeelException.class);
        }
    }
}
