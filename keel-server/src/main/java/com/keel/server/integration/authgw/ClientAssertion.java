package com.keel.server.integration.authgw;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public final class ClientAssertion {
    private ClientAssertion() {}

    public static String sign(String privatePem, String kid, String clientId, String audience) {
        try {
            var header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + kid + "\"}");
            var exp = Instant.now().getEpochSecond() + 300;
            var payload = b64("{\"iss\":\"" + clientId + "\",\"sub\":\"" + clientId + "\",\"aud\":\""
                    + audience + "\",\"exp\":" + exp + ",\"jti\":\"" + UUID.randomUUID() + "\"}");
            var signingInput = header + "." + payload;
            var signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey(privatePem));
            signature.update(signingInput.getBytes(StandardCharsets.US_ASCII));
            return signingInput + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.security.PrivateKey privateKey(String pem) throws Exception {
        var body = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
    }

    private static String b64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
