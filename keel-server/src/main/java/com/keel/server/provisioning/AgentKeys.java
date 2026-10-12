package com.keel.server.provisioning;

import org.springframework.stereotype.Component;

import java.math.BigInteger;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentKeys {
    private final ConcurrentHashMap<String, Material> keys = new ConcurrentHashMap<>();
    @org.springframework.beans.factory.annotation.Autowired
    private io.fabric8.kubernetes.client.KubernetesClient cluster;

    public Material generate(String agent) {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            var publicKey = (RSAPublicKey) pair.getPublic();
            var kid = Integer.toHexString(publicKey.getModulus().hashCode());
            var pem = "-----BEGIN PRIVATE KEY-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pair.getPrivate().getEncoded())
                    + "\n-----END PRIVATE KEY-----\n";
            var material = new Material(kid, publicKey, pem);
            keys.put(agent, material);
            return material;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    public Material find(String agent) {
        if (cluster != null) {
            try {
                var secret = cluster.secrets().inNamespace("agents").withName("keel-" + agent).get();
                if (secret != null && secret.getData() != null && secret.getData().containsKey("KEEL_CLIENT_PRIVATE_KEY")) {
                    var pem = new String(Base64.getDecoder().decode(secret.getData().get("KEEL_CLIENT_PRIVATE_KEY")), java.nio.charset.StandardCharsets.UTF_8);
                    return fromPem(pem);
                }
            } catch (Exception e) { return null; }
        }
        return keys.get(agent);
    }

    static Material fromPem(String pem) throws java.security.GeneralSecurityException {
        var encoded = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        var factory = java.security.KeyFactory.getInstance("RSA");
        var privateKey = (java.security.interfaces.RSAPrivateCrtKey) factory.generatePrivate(new java.security.spec.PKCS8EncodedKeySpec(Base64.getDecoder().decode(encoded)));
        var publicKey = (RSAPublicKey) factory.generatePublic(new java.security.spec.RSAPublicKeySpec(privateKey.getModulus(), privateKey.getPublicExponent()));
        return new Material(Integer.toHexString(publicKey.getModulus().hashCode()), publicKey, pem);
    }

    public void remove(String agent) {
        keys.remove(agent);
    }

    public Map<String, Object> jwks(String agent) {
        var material = find(agent);
        if (material == null) {
            return Map.of("keys", List.of());
        }
        return Map.of("keys", List.of(Map.of(
                "kty", "RSA",
                "use", "sig",
                "alg", "RS256",
                "kid", material.kid(),
                "n", unsigned(material.publicKey().getModulus()),
                "e", unsigned(material.publicKey().getPublicExponent()))));
    }

    private static String unsigned(BigInteger value) {
        var bytes = value.toByteArray();
        if (bytes[0] == 0) {
            bytes = java.util.Arrays.copyOfRange(bytes, 1, bytes.length);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public record Material(String kid, RSAPublicKey publicKey, String privatePem) {}
}
