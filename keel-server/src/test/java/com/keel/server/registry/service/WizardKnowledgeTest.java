package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.common.KeelException;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WizardKnowledgeTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void keepsKnownNamesAndRejectsUnknownOnes() throws Exception {
        var form = json.readTree("{\"knowledgeBases\":[\"demo\",\"demo\",\" 产品知识库 \"]}");
        assertThat(WizardKnowledge.resolve(form, Set.of("demo", "产品知识库")))
                .containsExactly("demo", "产品知识库");
        var missing = json.readTree("{\"knowledgeBases\":[\"missing\"]}");
        assertThatThrownBy(() -> WizardKnowledge.resolve(missing, Set.of("demo")))
                .isInstanceOf(KeelException.class)
                .hasMessageContaining("missing");
        assertThat(WizardKnowledge.resolve(json.readTree("{}"), Set.of("demo"))).isEmpty();
    }

    @Test void quotesNamesThatAreNotPlainScalars() {
        assertThat(WizardKnowledge.yamlScalar("demo")).isEqualTo("demo");
        assertThat(WizardKnowledge.yamlScalar("产品知识库")).isEqualTo("\"产品知识库\"");
    }
}
