package com.keel.server.devflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.PostgresTestConfig;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;
import com.keel.server.integration.agent.AgentEndpointClient;
import com.keel.server.registry.service.WizardCatalog;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(PostgresTestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class MetaAgentPersistenceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired PlatformTransactionManager transactions;
    @Autowired DevflowService jobs;
    @Autowired WizardCatalog catalog;
    @AfterEach void clear() { RequestContextHolder.resetRequestAttributes(); }

    @Test @Transactional void draftReviewDeliveryUsesOneJobAndSurvivesServiceReplacement() throws Exception {
        var request = new MockHttpServletRequest();
        ConsolePrincipal.set(request, ConsolePrincipal.dev());
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        var client = new FakeClient(
                new AgentEndpointClient.Answer("{\"spec\":{\"title\":\"QA\",\"goal\":\"需求\",\"users\":\"人\",\"io\":\"文档\",\"success\":\"通过\",\"pending\":[]},\"manifestYaml\":\"actual-yaml\"}", "trace-draft"),
                new AgentEndpointClient.Answer("{\"cases\":[{\"caseId\":\"a-1\",\"input\":{\"text\":\"bad\"},\"expected\":{\"status\":\"BLOCKED\"},\"tags\":[\"boundary\"],\"reason\":\"missing fields\"}]}", "trace-eval"),
                new AgentEndpointClient.Answer("{\"repo\":\"keel-agents/spec-agent\",\"number\":1,\"url\":\"https://github.com/keel-agents/spec-agent/pull/1\",\"sha\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"branch\":\"draft\"}", "trace-deliver"));
        var first = service(client);
        var job = first.create(MetaAgentRulesTest.draft("DEV", "CREATE", 0, 7), "QA");
        assertThat(job.status()).isEqualTo("QUEUED");
        first.tick();
        var restarted = service(client);
        assertThat(restarted.get(job.jobId()).path("phase").asText()).isEqualTo("WAITING");
        assertThat(restarted.review(job.jobId())).containsKey("execution");
        assertThatThrownBy(() -> restarted.decide(job.jobId(), new DevflowTypes.RequirementDecision("CONFIRM", 2, null, "人", "文档", "通过")))
                .isInstanceOf(KeelException.class);
        var confirm = new DevflowTypes.RequirementDecision("CONFIRM", 1, null, "人工编辑后的用户", "文档", "通过");
        restarted.decide(job.jobId(), confirm);
        restarted.decide(job.jobId(), confirm);
        assertThat(restarted.get(job.jobId()).path("phase").asText()).isEqualTo("WAITING_SEEDS");
        var seeds = new ArrayList<DevflowTypes.SeedCase>();
        for (int i = 1; i <= Math.max(1, jobs.settings().minSeed()); i++) {
            seeds.add(new DevflowTypes.SeedCase("h-" + i, Map.of("text", "valid-" + i),
                    Map.of("status", "READY"), List.of("normal")));
        }
        restarted.uploadHumanSeeds(job.jobId(), seeds);
        restarted.tick();
        assertThat(restarted.get(job.jobId()).path("phase").asText()).isEqualTo("WAITING_EVAL");
        assertThat(restarted.get(job.jobId()).has("humanSeeds")).isFalse();
        assertThat(restarted.review(job.jobId())).containsKey("cases");
        restarted.acceptEval(job.jobId(), new DevflowTypes.SeedAccept(List.of("a-1"), List.of()), "QA");
        assertThat(restarted.get(job.jobId()).has("visibleSeeds")).isTrue();
        assertThat(restarted.get(job.jobId()).has("humanSeeds")).isFalse();
        restarted.tick();
        assertThat(restarted.get(job.jobId()).path("phase").asText()).isEqualTo("SANDBOX_PENDING");
        assertThat(restarted.get(job.jobId()).path("draft").path("spec").path("users").asText()).isEqualTo("人工编辑后的用户");
        assertThat(jobs.job(job.jobId()).status()).isEqualTo("RUN");
        restarted.decide(job.jobId(), confirm);
        restarted.tick();
        assertThat(client.calls).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM devflow_execution WHERE job_id=?", Integer.class, job.jobId())).isEqualTo(1);
    }

    private static class FakeClient extends AgentEndpointClient {
        private final Answer[] answers;
        int calls;
        FakeClient(Answer... answers) { this.answers = answers; }
        @Override public Answer invokeWork(String endpoint, String text, String bridgeToken, String user) {
            return answers[calls++];
        }
    }

    private MetaAgentExecution service(AgentEndpointClient client) {
        return new MetaAgentExecution(jdbc, json, transactions, jobs, catalog, client, "http://meta-agent", "test-bridge");
    }
}
