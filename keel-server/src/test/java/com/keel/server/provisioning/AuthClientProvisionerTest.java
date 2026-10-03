package com.keel.server.provisioning;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.integration.authgw.AuthGatewayClient;
import com.keel.server.integration.authgw.ClientAssertion;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.Signature;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class AuthClientProvisionerTest {
    @Test void registersIdempotentlyAndSignsWithTheHostedKey() throws Exception {
        var posts = new AtomicInteger();
        var bodies = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var keys = new AgentKeys();
        server.createContext("/internal/clients", exchange -> {
            if ("POST".equals(exchange.getRequestMethod())) {
                posts.incrementAndGet();
                bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            }
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
        });
        server.createContext("/oauth/token-exchange", exchange -> {
            var form = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            var assertion = java.net.URLDecoder.decode(form.split("client_assertion=")[1].split("&")[0], StandardCharsets.UTF_8);
            var ok = false;
            try {
                var parts = assertion.split("\\.");
                var signature = Signature.getInstance("SHA256withRSA");
                signature.initVerify(keys.find("code-review").publicKey());
                signature.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
                ok = signature.verify(Base64.getUrlDecoder().decode(parts[2]));
            } catch (Exception ignored) {
                ok = false;
            }
            exchange.sendResponseHeaders(ok ? 200 : 401, -1);
            exchange.close();
        });
        server.start();
        try {
            var secrets = new SecretWriter();
            var base = "http://127.0.0.1:" + server.getAddress().getPort();
            var provisioner = new AuthClientProvisioner(keys, secrets, new AuthGatewayClient(base), base);
            var manifest = new ObjectMapper().readTree("""
                    {"spec":{"auth":{"audience":"code-review"},"delegates":["askdb","offshore-wind"]}}
                    """);
            provisioner.provision("code-review", "dev", manifest);
            provisioner.provision("code-review", "dev", manifest);
            assertThat(posts.get()).isEqualTo(2);
            assertThat(bodies.get(1)).contains("keel-api", "code-review", "askdb", "offshore-wind");

            var jwks = new AgentJwksController(keys).jwks("code-review").toString();
            assertThat(jwks).contains(keys.find("code-review").kid());
            assertThat(jwks).doesNotContain("PRIVATE KEY");
            assertThat(secrets.privateKeyFor("code-review")).contains("BEGIN PRIVATE KEY");

            var assertion = ClientAssertion.sign(secrets.privateKeyFor("code-review"),
                    keys.find("code-review").kid(), "code-review", base + "/oauth/token-exchange");
            assertThat(new AuthGatewayClient(base).exchange(assertion)).isEqualTo(200);

            provisioner.revoke("code-review", "dev");
            assertThat(new AgentJwksController(keys).jwks("code-review").get("keys")).isEqualTo(List.of());
        } finally {
            server.stop(0);
        }
    }
}
