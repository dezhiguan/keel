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

    @Test void registeredReadinessUsesTheExactEnvironmentManifest() throws Exception {
        readiness("staging", false);
    }

    @Test void prodPreviewDoesNotPersistARegistrationBeforeItsGatePasses() throws Exception {
        readiness("prod", true);
    }

    private void readiness(String env, boolean preview) throws Exception {
        var request = new MockHttpServletRequest();
        ConsolePrincipal.set(request, ConsolePrincipal.service("trusted-ci"));
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var json = new ObjectMapper();
        var manifest = json.createObjectNode();
        manifest.putObject("spec").putObject("runtime").put("endpoint", "http://qa-" + env);
        var context = json.createObjectNode().put("sha", "a".repeat(40)).put("agent", "qa");
        context.set("manifest", manifest);
        var execution = new MetaAgentExecution(null, json, null, null, null, null, "", "") {
            @Override public com.fasterxml.jackson.databind.node.ObjectNode pipelineContext(String id, String target, boolean snapshot) {
                assertThat(id).isEqualTo("DF-0010"); assertThat(target).isEqualTo(env); assertThat(snapshot).isFalse();
                return context;
            }
        };
        var failed = new com.keel.server.registry.service.SelfCheckService.Report(false, java.util.List.of());
        class Probe extends com.keel.server.registry.service.SelfCheckService {
            boolean called;
            @Override public Report checkRuntime(String name, String target, String endpoint, com.fasterxml.jackson.databind.JsonNode actual) {
                assertThat(preview).isTrue(); called = true;
                assertThat(actual).isEqualTo(manifest);
                return failed;
            }
            @Override public Report checkRegistered(String name, String target, String endpoint, com.fasterxml.jackson.databind.JsonNode actual) {
                assertThat(preview).isFalse(); called = true;
                assertThat(actual).isEqualTo(manifest.deepCopy().put("env", env));
                return failed;
            }
        }
        var checks = new Probe();
        var controller = new DevflowPipelineController(execution, null, checks, null, null, null,
                json, null, "trusted-ci");
        controller.step("DF-0010", "ready", json.createObjectNode().put("env", env).put("sha", "a".repeat(40)));
        assertThat(checks.called).isTrue();
    }
}
