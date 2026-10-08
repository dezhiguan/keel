package com.keel.server.auth;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.authgw.ClientAssertion;
import com.keel.server.provisioning.AgentKeys;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.time.Instant;
import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceIdentityFilterTest {
    private static final String AUDIENCE = "keel-api";

    @Test void catalogReadsAcceptASignedAgent() throws Exception {
        var keys = new AgentKeys();
        var material = keys.generate("coder");
        var token = ClientAssertion.sign(material.privatePem(), material.kid(), "coder", AUDIENCE);
        var identity = new ServiceIdentityAuthenticator(keys, acceptingReplay(), AUDIENCE);
        var filter = filter(identity);
        var request = catalogGet("/api/v1/tools/alarm_query");
        request.addHeader("X-Client-Id", "coder");
        request.addHeader("X-Client-Assertion-Type", ServiceAssertion.TYPE);
        request.addHeader("X-Client-Assertion", token);
        var called = new boolean[]{false};
        filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> called[0] = true);
        assertThat(called[0]).isTrue();
        assertThat(request.getAttribute(ConsolePrincipal.ATTRIBUTE)).isEqualTo(ConsolePrincipal.service("coder"));
    }

    @Test void aBadAssertionDoesNotFallThroughToTheConsoleSession() throws Exception {
        var identity = new ServiceIdentity() {
            @Override public boolean applies(jakarta.servlet.http.HttpServletRequest request) { return true; }
            @Override public void authenticate(jakarta.servlet.http.HttpServletRequest request) {
                throw new KeelException(ErrorCode.AUTH_UNAUTHENTICATED, ErrorCode.AUTH_UNAUTHENTICATED.message());
            }
        };
        var response = new MockHttpServletResponse();
        var called = new boolean[]{false};
        filter(identity).doFilter(catalogGet("/api/v1/tools"), response, (req, res) -> called[0] = true);
        assertThat(called[0]).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("AUTH_UNAUTHENTICATED");
    }

    @Test void writesAndNestedRoutesStayOnTheConsoleSession() {
        assertThat(CatalogAccess.readable("GET", "/api/v1/tools")).isTrue();
        assertThat(CatalogAccess.readable("HEAD", "/api/v1/tools/alarm_query")).isTrue();
        assertThat(CatalogAccess.readable("POST", "/api/v1/tools/alarm_query/grants")).isFalse();
        assertThat(CatalogAccess.readable("GET", "/api/v1/tools/alarm_query/versions")).isFalse();
        assertThat(CatalogAccess.readable("GET", "/api/v1/tools/")).isFalse();
    }

    @Test void aReplayedJtiIsRejectedAndAnUnknownAgentIsRejected() {
        var keys = new AgentKeys();
        var material = keys.generate("coder");
        var token = ClientAssertion.sign(material.privatePem(), material.kid(), "coder", AUDIENCE);
        var seen = new HashSet<String>();
        var identity = new ServiceIdentityAuthenticator(keys, (jti, expiresAt) -> {
            if (!seen.add(jti)) {
                throw new KeelException(ErrorCode.AUTH_UNAUTHENTICATED, ErrorCode.AUTH_UNAUTHENTICATED.message());
            }
        }, AUDIENCE);
        var request = catalogGet("/api/v1/tools");
        request.addHeader("X-Client-Id", "coder");
        request.addHeader("X-Client-Assertion-Type", ServiceAssertion.TYPE);
        request.addHeader("X-Client-Assertion", token);
        identity.authenticate(request);
        assertThatThrownBy(() -> identity.authenticate(request)).isInstanceOf(KeelException.class);
        request.removeHeader("X-Client-Id");
        request.addHeader("X-Client-Id", "missing");
        assertThatThrownBy(() -> identity.authenticate(request)).isInstanceOf(KeelException.class);
        assertThat(new ServiceIdentityAuthenticator(keys, acceptingReplay(), "  ").applies(request)).isFalse();
    }

    private static ConsoleSessionFilter filter(ServiceIdentity identity) {
        var properties = new ConsoleAuthProperties();
        properties.getAuth().setOpenForTests(false);
        return new ConsoleSessionFilter(properties, null, null, null, identity);
    }

    private static MockHttpServletRequest catalogGet(String uri) {
        return new MockHttpServletRequest("GET", uri);
    }

    private static AssertionReplay acceptingReplay() {
        return (jti, expiresAt) -> assertThat(expiresAt).isAfter(Instant.now().minusSeconds(1));
    }
}
