package com.keel.server.devflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.auth.CatalogAccess;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import static org.assertj.core.api.Assertions.*;

class DevflowPipelineSecurityTest {
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }

    @Test void employeeIdentityCannotUseCiProvisioningOrReadCandidateContext() {
        var request = new MockHttpServletRequest();
        ConsolePrincipal.set(request, ConsolePrincipal.service("employee"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var controller = new DevflowPipelineController(null, null, null, null, null, null,
                new ObjectMapper(), null, "trusted-ci");
        assertThatThrownBy(() -> controller.step("DF-0010", "context", new ObjectMapper().createObjectNode().put("env", "staging")))
                .isInstanceOf(KeelException.class);
    }

    @Test void serviceRoutesPermitOnlyCiActionsAndHoldoutNotConsoleMutation() {
        assertThat(CatalogAccess.serviceAccessible("POST", "/api/v1/devflow/ci/jobs/DF-0010/prepare")).isTrue();
        assertThat(CatalogAccess.serviceAccessible("GET", "/api/v1/devflow/holdout/DF-0010")).isTrue();
        assertThat(CatalogAccess.serviceAccessible("POST", "/api/v1/devflow/holdout-results")).isTrue();
        assertThat(CatalogAccess.serviceAccessible("POST", "/api/v1/devflow/jobs/DF-0010/pipeline/run")).isFalse();
        assertThat(CatalogAccess.serviceAccessible("POST", "/api/v1/agents")).isFalse();
    }
}
