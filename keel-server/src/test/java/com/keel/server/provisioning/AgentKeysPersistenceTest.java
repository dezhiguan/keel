package com.keel.server.provisioning;

import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.server.mock.KubernetesMockServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.assertj.core.api.Assertions.*;

class AgentKeysPersistenceTest {
    @Test void anotherReplicaAndARestartReadTheCurrentSecretKey() throws Exception {
        var server=new KubernetesMockServer(); server.start();
        try(var client=server.createClient()) {
            var registering=new AgentKeys();
            var material=registering.generate("qa-auth");
            var secret=new SecretBuilder().withNewMetadata().withName("keel-qa-auth").endMetadata()
                    .addToData("KEEL_CLIENT_PRIVATE_KEY",Base64.getEncoder().encodeToString(material.privatePem().getBytes(StandardCharsets.UTF_8))).build();
            server.expect().get().withPath("/api/v1/namespaces/agents/secrets/keel-qa-auth").andReturn(200,secret).always();
            var replica=new AgentKeys();
            ReflectionTestUtils.setField(replica,"cluster",client);
            assertThat(replica.find("qa-auth").publicKey()).isEqualTo(material.publicKey());
            assertThat(replica.jwks("qa-auth").get("keys")).isNotNull();
        } finally {server.destroy();}
    }
}
