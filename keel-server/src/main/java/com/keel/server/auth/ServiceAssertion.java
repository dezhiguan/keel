package com.keel.server.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;

import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Base64;

/** RS256 client assertion. iss and sub are the client id. exp is at most 10 minutes after iat. */
public final class ServiceAssertion {
    public static final String TYPE = "urn:ietf:params:oauth:client-assertion-type:jwt-bearer";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final long MAX_TTL_SECONDS = 600;

    private ServiceAssertion() {}

    public record Verified(String clientId, String jti, Instant expiresAt) {}

    public static Verified verify(String token, String audience, RSAPublicKey key, Instant now) {
        if (token == null || audience == null || audience.isBlank() || key == null || now == null) {
            throw denied();
        }
        var parts = token.split("\\.");
        if (parts.length != 3 || !verifySignature(parts[0] + "." + parts[1], parts[2], key)) {
            throw denied();
        }
        JsonNode claims;
        try {
            claims = JSON.readTree(decode(parts[1]));
        } catch (Exception e) {
            throw denied();
        }
        var clientId = text(claims, "iss");
        if (clientId.isBlank() || !clientId.equals(text(claims, "sub"))) {
            throw denied();
        }
        if (!audience.equals(text(claims, "aud"))) {
            throw denied();
        }
        var exp = claims.path("exp").asLong(0);
        var iat = claims.path("iat").asLong(0);
        if (exp <= now.getEpochSecond()) {
            throw denied();
        }
        if (iat > 0 && exp - iat > MAX_TTL_SECONDS) {
            throw denied();
        }
        if (iat == 0 && exp - now.getEpochSecond() > MAX_TTL_SECONDS) {
            throw denied();
        }
        var jti = text(claims, "jti");
        if (jti.isBlank() || jti.length() > 128) {
            throw denied();
        }
        return new Verified(clientId, jti, Instant.ofEpochSecond(exp));
    }

    private static boolean verifySignature(String signingInput, String signaturePart, RSAPublicKey key) {
        try {
            var signature = Signature.getInstance("SHA256withRSA");
            signature.initVerify(key);
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signature.verify(decode(signaturePart));
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] decode(String value) {
        var padded = value + "=".repeat((4 - value.length() % 4) % 4);
        return Base64.getUrlDecoder().decode(padded);
    }

    private static String text(JsonNode node, String field) {
        var value = node.path(field);
        return value.isTextual() ? value.asText("") : "";
    }

    private static KeelException denied() {
        return new KeelException(ErrorCode.AUTH_UNAUTHENTICATED, ErrorCode.AUTH_UNAUTHENTICATED.message());
    }
}
