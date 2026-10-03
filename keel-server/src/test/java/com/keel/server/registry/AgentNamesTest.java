package com.keel.server.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.registry.service.AgentNames;
import com.keel.server.registry.service.ManifestPreview;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentNamesTest {
    @Test void rejectsBlankAndUppercaseNames() {
        assertThat(AgentNames.rejection(null)).isNotBlank();
        assertThat(AgentNames.rejection("Ask")).isNotBlank();
        assertThat(AgentNames.rejection("a")).isNotBlank();
    }

    @Test void acceptsANormalName() {
        assertThat(AgentNames.rejection("askdb")).isNull();
    }

    @Test void previewContainsTheContractHeader() throws Exception {
        var form = new ObjectMapper().readTree("""
                {"name":"code-review","displayName":"代码评审","ownerOrg":"研发效能组","ownerUser":"amy","language":"Python","endpoint":"http://code-review.agents.svc:8000"}
                """);
        var rendered = ManifestPreview.render(form);
        assertThat(rendered.yaml()).contains("apiVersion: keel/v1");
        assertThat(rendered.yaml()).contains("name: code-review");
        assertThat(rendered.yaml()).contains("language: python");
        assertThat(rendered.warnings()).isEmpty();
    }
}