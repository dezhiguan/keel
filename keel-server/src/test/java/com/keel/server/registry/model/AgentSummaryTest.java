package com.keel.server.registry.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.registry.model.dto.AgentSummary;
import com.keel.server.registry.model.entity.Agent;
import com.keel.server.registry.model.entity.AgentVersion;
import com.keel.server.registry.model.enums.AgentStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AgentSummaryTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void cardFieldsComeFromTheRegisteredVersion() throws Exception {
        var agent = agent("askdb", "问数", "python");
        var version = new AgentVersion();
        version.setEnv("prod");
        version.setVersion("v1.9.0");
        var manifest = json.readTree("""
                {"metadata":{"category":"biz"},"spec":{"models":{"budget":{"dailyCny":40}},"delegates":["offshore-wind"]}}
                """);
        var summary = AgentSummary.of(agent, version, "2/2", manifest);
        assertThat(summary.category()).isEqualTo("biz");
        assertThat(summary.env()).isEqualTo("prod");
        assertThat(summary.version()).isEqualTo("v1.9.0");
        assertThat(summary.dailyBudgetCny()).isEqualTo(40);
        assertThat(summary.delegateCount()).isEqualTo(1);
        assertThat(summary.multiAgent()).isTrue();
        assertThat(summary.calls24h()).isNull();
        assertThat(summary.costCny()).isNull();
        assertThat(summary.score()).isNull();
    }

    @Test void knownAgentsKeepTheirDocumentedCategoryUntilTheManifestSaysOtherwise() {
        assertThat(AgentCategories.resolve("careermate", null)).isEqualTo("biz");
        assertThat(AgentCategories.resolve("code-review", null)).isEqualTo("dev");
        assertThat(AgentCategories.resolve("echo", null)).isNull();
    }

    @Test void usageReplacesOnlyTheThreeMetrics() {
        var filled = AgentSummary.of(agent("askdb", "问数", "python"), null, null, null)
                .withUsage(3L, null, null, 1.25, null, 0.88);
        assertThat(filled.calls24h()).isEqualTo(3L);
        assertThat(filled.costCny()).isEqualTo(1.25);
        assertThat(filled.score()).isEqualTo(0.88);
        assertThat(filled.category()).isEqualTo("biz");
    }

    private static Agent agent(String name, String displayName, String language) {
        var agent = new Agent();
        agent.setName(name);
        agent.setDisplayName(displayName);
        agent.setLanguage(language);
        agent.setRuntime("code");
        agent.setStatus(AgentStatus.REGISTERED);
        agent.setOwnerOrg("数据平台组");
        agent.setOwnerUser("zhou");
        return agent;
    }
}
