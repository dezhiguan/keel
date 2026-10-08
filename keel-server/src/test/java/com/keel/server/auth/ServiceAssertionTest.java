package com.keel.server.auth;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.authgw.ClientAssertion;
import com.keel.server.provisioning.AgentKeys;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ServiceAssertionTest {
    private static final String AUDIENCE = "keel-api";

    @Test void acceptsTheAssertionKeelSigns() {
        var material = new AgentKeys().generate("coder");
        var token = ClientAssertion.sign(material.privatePem(), material.kid(), "coder", AUDIENCE);
        var verified = ServiceAssertion.verify(token, AUDIENCE, material.publicKey(), Instant.now());
        assertThat(verified.clientId()).isEqualTo("coder");
        assertThat(verified.jti()).isNotBlank();
        assertThat(verified.expiresAt()).isAfter(Instant.now());
    }

    @Test void rejectsABadSignatureAudienceExpiryAndSubject() {
        var material = new AgentKeys().generate("coder");
        var now = Instant.now();
        var token = ClientAssertion.sign(material.privatePem(), material.kid(), "coder", AUDIENCE);
        var broken = token.substring(0, token.lastIndexOf('.') + 1) + "not-a-signature";
        assertThatThrownBy(() -> ServiceAssertion.verify(broken, AUDIENCE, material.publicKey(), now))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.AUTH_UNAUTHENTICATED);
        assertThatThrownBy(() -> ServiceAssertion.verify(token, "other", material.publicKey(), now))
                .isInstanceOf(KeelException.class);
        var expired = sign(material, "{\"iss\":\"coder\",\"sub\":\"coder\",\"aud\":\"" + AUDIENCE
                + "\",\"exp\":" + now.minusSeconds(5).getEpochSecond() + ",\"jti\":\"jti-old\"}");
        assertThatThrownBy(() -> ServiceAssertion.verify(expired, AUDIENCE, material.publicKey(), now))
                .isInstanceOf(KeelException.class);
        var swapped = sign(material, "{\"iss\":\"coder\",\"sub\":\"other\",\"aud\":\"" + AUDIENCE
                + "\",\"exp\":" + now.plusSeconds(60).getEpochSecond() + ",\"jti\":\"jti-swap\"}");
        assertThatThrownBy(() -> ServiceAssertion.verify(swapped, AUDIENCE, material.publicKey(), now))
                .isInstanceOf(KeelException.class);
    }

    @Test void rejectsAnAssertionThatLivesLongerThanTenMinutes() {
        var material = new AgentKeys().generate("coder");
        var now = Instant.now();
        var far = sign(material, "{\"iss\":\"coder\",\"sub\":\"coder\",\"aud\":\"" + AUDIENCE
                + "\",\"exp\":" + now.plusSeconds(601).getEpochSecond() + ",\"jti\":\"jti-far\"}");
        assertThatThrownBy(() -> ServiceAssertion.verify(far, AUDIENCE, material.publicKey(), now))
                .isInstanceOf(KeelException.class);
        var wide = sign(material, "{\"iss\":\"coder\",\"sub\":\"coder\",\"aud\":\"" + AUDIENCE
                + "\",\"iat\":" + now.getEpochSecond() + ",\"exp\":" + now.plusSeconds(601).getEpochSecond()
                + ",\"jti\":\"jti-wide\"}");
        assertThatThrownBy(() -> ServiceAssertion.verify(wide, AUDIENCE, material.publicKey(), now))
                .isInstanceOf(KeelException.class);
    }

    private static String sign(AgentKeys.Material material, String payload) {
        try {
            var header = b64("{\"alg\":\"RS256\",\"typ\":\"JWT\",\"kid\":\"" + material.kid() + "\"}");
            var body = b64(payload);
            var input = header + "." + body;
            var signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey(material.privatePem()));
            signature.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature.sign());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static java.security.PrivateKey privateKey(String pem) throws Exception {
        var body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
    }

    private static String b64(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
}
