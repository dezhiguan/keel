package com.keel.server.auth;

import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;

@Component
public class ConsoleSigningKey {
    private final String kid;
    private final RSAPublicKey publicKey;
    private final RSAPrivateKey privateKey;
    private final String privatePem;

    public ConsoleSigningKey() {
        String configured = System.getenv("KEEL_CONSOLE_CLIENT_PRIVATE_KEY");
        if (configured != null && !configured.isBlank()) {
            this.privatePem = configured.trim();
            this.privateKey = parse(privatePem);
            this.publicKey = null;
            this.kid = "keel-console-backend";
            return;
        }
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            this.privateKey = (RSAPrivateKey) pair.getPrivate();
            this.publicKey = (RSAPublicKey) pair.getPublic();
            this.kid = "local";
            this.privatePem = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(privateKey.getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public String kid() { return kid; }

    public String privatePem() { return privatePem; }

    public RSAPrivateKey privateKey() { return privateKey; }

    public RSAPublicKey publicKey() {
        if (publicKey != null) return publicKey;
        try {
            var factory = KeyFactory.getInstance("RSA");
            if (!(privateKey instanceof RSAPrivateCrtKey crt)) {
                throw new IllegalStateException("控制台私钥不是 CRT 格式");
            }
            var spec = new RSAPublicKeySpec(crt.getModulus(), crt.getPublicExponent());
            return (RSAPublicKey) factory.generatePublic(spec);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Map<String, Object> jwks() {
        RSAPublicKey key = publicKey();
        return Map.of("keys", List.of(Map.of(
                "kty", "RSA",
                "use", "sig",
                "alg", "RS256",
                "kid", kid,
                "n", unsigned(key.getModulus()),
                "e", unsigned(key.getPublicExponent()))));
    }

    private static RSAPrivateKey parse(String pem) {
        try {
            var body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s", "");
            return (RSAPrivateKey) KeyFactory.getInstance("RSA")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
        } catch (Exception e) {
            throw new IllegalStateException("KEEL_CONSOLE_CLIENT_PRIVATE_KEY 无法解析", e);
        }
    }

    private static String unsigned(BigInteger value) {
        var bytes = value.toByteArray();
        if (bytes[0] == 0) bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
