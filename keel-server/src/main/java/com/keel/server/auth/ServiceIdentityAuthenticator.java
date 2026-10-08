package com.keel.server.auth;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.provisioning.AgentKeys;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class ServiceIdentityAuthenticator implements ServiceIdentity {
    private final AgentKeys keys;
    private final AssertionReplay replay;
    private final String audience;

    public ServiceIdentityAuthenticator(AgentKeys keys, AssertionReplay replay,
                                        @Value("${keel.service.audience:}") String audience) {
        this.keys = keys;
        this.replay = replay;
        this.audience = audience == null ? "" : audience.trim();
    }

    @Override
    public boolean applies(HttpServletRequest request) {
        return audiencePresent() && headerPresent(request);
    }

    @Override
    public void authenticate(HttpServletRequest request) {
        if (!audiencePresent()) {
            throw denied();
        }
        var clientId = header(request, "X-Client-Id");
        var type = header(request, "X-Client-Assertion-Type");
        var assertion = header(request, "X-Client-Assertion");
        if (clientId.isBlank() || !ServiceAssertion.TYPE.equals(type) || assertion.isBlank()) {
            throw denied();
        }
        var material = keys.find(clientId);
        if (material == null) {
            throw denied();
        }
        var verified = ServiceAssertion.verify(assertion, audience, material.publicKey(), Instant.now());
        if (!clientId.equals(verified.clientId())) {
            throw denied();
        }
        replay.remember(verified.jti(), verified.expiresAt());
        ConsolePrincipal.set(request, ConsolePrincipal.service(verified.clientId()));
    }

    private boolean audiencePresent() {
        return !audience.isBlank();
    }

    private static boolean headerPresent(HttpServletRequest request) {
        return !header(request, "X-Client-Id").isBlank()
                || !header(request, "X-Client-Assertion-Type").isBlank()
                || !header(request, "X-Client-Assertion").isBlank();
    }

    private static String header(HttpServletRequest request, String name) {
        var value = request.getHeader(name);
        return value == null ? "" : value.trim();
    }

    private static KeelException denied() {
        return new KeelException(ErrorCode.AUTH_UNAUTHENTICATED, ErrorCode.AUTH_UNAUTHENTICATED.message());
    }
}
