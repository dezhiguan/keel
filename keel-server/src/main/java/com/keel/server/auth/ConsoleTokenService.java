package com.keel.server.auth;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ConsoleTokenService {
    static final String LOCAL_ISSUER = "keel-server-local";
    static final String AUDIENCE = "keel-console";
    static final long ACCESS_TTL = 900;
    static final long REFRESH_TTL = 604800;

    private final ConsoleSigningKey key;
    private final ConsoleAuthProperties properties;
    private final ConcurrentHashMap<String, Instant> localRefresh = new ConcurrentHashMap<>();
    private volatile NimbusJwtDecoder remoteDecoder;

    public ConsoleTokenService(ConsoleSigningKey key, ConsoleAuthProperties properties) {
        this.key = key;
        this.properties = properties;
    }

    public Issued issueLocal(String userId, String username, String platformRole) {
        String refresh = "rt_" + UUID.randomUUID();
        localRefresh.put(refresh, Instant.now().plusSeconds(REFRESH_TTL));
        return new Issued(sign(userId, username, platformRole), refresh, ACCESS_TTL, REFRESH_TTL);
    }

    public Issued rotateLocal(String refresh) {
        Instant expires = localRefresh.remove(refresh);
        if (expires == null || expires.isBefore(Instant.now())) return null;
        return issueLocal("local-guandezhi", ConsoleUsers.GUAN.username(), ConsoleUsers.GUAN.platformRole());
    }

    public void revokeLocal(String refresh) {
        if (refresh != null) localRefresh.remove(refresh);
    }

    public VerifyResult verify(String token) {
        if (token == null || token.isBlank()) return VerifyResult.absent();
        if ("local".equals(properties.mode())) return verifyLocal(token);
        return verifyRemote(token);
    }

    private VerifyResult verifyLocal(String token) {
        try {
            SignedJWT jwt = SignedJWT.parse(token);
            if (!jwt.verify(new RSASSAVerifier(key.publicKey()))) return VerifyResult.invalid();
            var claims = jwt.getJWTClaimsSet();
            if (claims.getExpirationTime() == null || claims.getExpirationTime().before(new Date())) return VerifyResult.expired();
            if (claims.getAudience() == null || !claims.getAudience().contains(AUDIENCE)) return VerifyResult.audience();
            if (!LOCAL_ISSUER.equals(claims.getIssuer())) return VerifyResult.audience();
            Object rawId = claims.getClaim("user_id");
            String username = claims.getStringClaim("username");
            return VerifyResult.ok(rawId == null ? "" : String.valueOf(rawId), username == null ? "" : username, stringList(claims.getClaim("roles")));
        } catch (Exception e) {
            return VerifyResult.invalid();
        }
    }

    private VerifyResult verifyRemote(String token) {
        try {
            Jwt jwt = remote().decode(token);
            if (!jwt.getAudience().contains(AUDIENCE)) return VerifyResult.audience();
            if (jwt.getIssuer() == null || !properties.getAuth().getIssuer().equals(jwt.getIssuer().toString())) {
                return VerifyResult.audience();
            }
            Object rawId = jwt.getClaim("user_id");
            String username = jwt.getClaimAsString("username");
            return VerifyResult.ok(rawId == null ? "" : String.valueOf(rawId), username == null ? "" : username, roles(jwt));
        } catch (JwtException e) {
            String message = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
            if (message.contains("expired")) return VerifyResult.expired();
            return VerifyResult.invalid();
        }
    }

    private String sign(String userId, String username, String platformRole) {
        try {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(LOCAL_ISSUER)
                    .audience(AUDIENCE)
                    .subject("user:" + userId)
                    .jwtID("jti_" + UUID.randomUUID())
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plusSeconds(ACCESS_TTL)))
                    .claim("user_id", userId)
                    .claim("username", username)
                    .claim("platform_role", platformRole)
                    .claim("roles", List.of(platformRole))
                    .build();
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.kid()).type(JOSEObjectType.JWT).build(), claims);
            jwt.sign(new RSASSASigner(key.privateKey()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private NimbusJwtDecoder remote() {
        if (remoteDecoder == null) {
            String base = System.getenv("KEEL_AUTH_GATEWAY_URL");
            if (base == null || base.isBlank()) {
                throw new JwtException("auth-gateway jwks missing");
            }
            remoteDecoder = NimbusJwtDecoder.withJwkSetUri(base.replaceAll("/$", "") + "/.well-known/jwks.json").build();
        }
        return remoteDecoder;
    }

    private static List<String> stringList(Object claim) {
        if (!(claim instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    private static List<String> roles(Jwt jwt) {
        Object claim = jwt.getClaim("roles");
        if (!(claim instanceof List<?> list)) return List.of();
        return list.stream().map(String::valueOf).toList();
    }

    public record Issued(String accessToken, String refreshToken, long expiresIn, long refreshExpiresIn) {}

    public record VerifyResult(Kind kind, String userId, String username, List<String> roles) {
        public enum Kind { ABSENT, OK, EXPIRED, AUDIENCE, INVALID }

        static VerifyResult absent() { return new VerifyResult(Kind.ABSENT, "", "", List.of()); }
        static VerifyResult expired() { return new VerifyResult(Kind.EXPIRED, "", "", List.of()); }
        static VerifyResult audience() { return new VerifyResult(Kind.AUDIENCE, "", "", List.of()); }
        static VerifyResult invalid() { return new VerifyResult(Kind.INVALID, "", "", List.of()); }
        static VerifyResult ok(String userId, String username, List<String> roles) {
            return new VerifyResult(Kind.OK, userId, username, roles);
        }
    }
}
