package com.keel.server.integration.authgw;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Component;

import java.net.URI;

/** Verifies a business user's keel-api token against auth-gateway JWKS. */
@Component
public class ChannelJwtVerifier {
    private final String jwks;
    private final String issuer;
    private volatile NimbusJwtDecoder decoder;

    public ChannelJwtVerifier(@Value("${KEEL_AUTH_GATEWAY_URL:}") String authGateway,
                              @Value("${KEEL_AUTH_ISSUER:https://auth.careermate.cn}") String issuer) {
        jwks = authGateway == null || authGateway.isBlank() ? "" : authGateway.replaceAll("/$", "") + "/.well-known/jwks.json";
        this.issuer = issuer;
    }

    public String user(String authorization) {
        return user(authorization, null);
    }

    public String user(String authorization, String clientId) {
        if (jwks.isBlank() || authorization == null || !authorization.startsWith("Bearer ")) throw denied();
        try {
            var jwt = decoder().decode(authorization.substring(7).trim());
            if (!jwt.getAudience().contains("keel-api") || !URI.create(issuer).equals(jwt.getIssuer())
                    || !"user".equals(jwt.getClaimAsString("principal_type"))
                    || jwt.getSubject() == null || jwt.getSubject().isBlank()
                    || (clientId != null && !clientId.equals(jwt.getClaimAsString("azp")))) throw denied();
            return jwt.getSubject();
        } catch (JwtException | IllegalArgumentException error) {
            throw denied();
        }
    }

    private NimbusJwtDecoder decoder() {
        var current = decoder;
        if (current == null) {
            synchronized (this) {
                if (decoder == null) decoder = NimbusJwtDecoder.withJwkSetUri(jwks).build();
                current = decoder;
            }
        }
        return current;
    }

    private static KeelException denied() {
        return new KeelException(ErrorCode.AUTH_UNAUTHENTICATED, ErrorCode.AUTH_UNAUTHENTICATED.message());
    }
}
