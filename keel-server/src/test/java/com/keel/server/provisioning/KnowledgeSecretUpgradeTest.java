package com.keel.server.provisioning;

import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.utils.KubernetesSerialization;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static org.assertj.core.api.Assertions.assertThat;

class KnowledgeSecretUpgradeTest {
    @Test void serverManagedFieldsAreNotWrittenBackAndAllOtherCredentialsArePreserved() {
        var serialization=new KubernetesSerialization();
        Secret original=serialization.unmarshal("""
            {"apiVersion":"v1","kind":"Secret","metadata":{"name":"keel-assistant","namespace":"agents",
              "resourceVersion":"42","labels":{"keel.io/env":"staging"},"annotations":{"keel.io/config":"retained"},
              "managedFields":[{"manager":"kubectl","operation":"Update","apiVersion":"v1",
                 "fieldsType":"FieldsV1","fieldsV1":{"f:data":{"f:KEEL_LLM_KEY":{},"f:KEEL_ENV":{}}}}]},
             "data":{"KEEL_LLM_KEY":"bW9kZWwtZml4dHVyZQ==","KEEL_CLIENT_PRIVATE_KEY":"a2V5LWZpeHR1cmU=","KEEL_ENV":"c3RhZ2luZw=="}}
            """,Secret.class);
        var updated=SecretWriter.knowledgeSecret(original,new RagForgeAccess.Issued(99,"read-only-fixture","https://knowledge.fixture.invalid","686"));
        assertThat(updated.getMetadata().getManagedFields()).isNullOrEmpty();
        assertThat(original.getMetadata().getManagedFields()).hasSize(1);
        assertThat(updated.getMetadata().getName()).isEqualTo(original.getMetadata().getName());
        assertThat(updated.getMetadata().getNamespace()).isEqualTo(original.getMetadata().getNamespace());
        assertThat(updated.getMetadata().getResourceVersion()).isEqualTo("42");
        assertThat(updated.getMetadata().getLabels()).isEqualTo(original.getMetadata().getLabels());
        assertThat(updated.getMetadata().getAnnotations()).isEqualTo(original.getMetadata().getAnnotations());
        assertThat(updated.getData()).containsAllEntriesOf(original.getData());
        assertThat(original.getData()).doesNotContainKey("KEEL_RAGFORGE_TOKEN");
        assertThat(new String(Base64.getDecoder().decode(updated.getData().get("KEEL_ASSISTANT_KB_ID")),StandardCharsets.UTF_8)).isEqualTo("686");
        assertThat(serialization.asJson(updated)).contains("KEEL_RAGFORGE_TOKEN").doesNotContain("fieldsV1");
    }
}
