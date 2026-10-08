package com.keel.server.approval;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(com.keel.server.PostgresTestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class ApprovalFlowTest {
    private static HttpServer agent;
    private static final List<String> RESUMES = new ArrayList<>();

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;

    @BeforeAll
    static void agent() throws Exception {
        agent = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        agent.createContext("/", exchange -> {
            var path = exchange.getRequestURI().getPath();
            var body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            RESUMES.add(path + " " + body);
            var code = path.contains("down-run") ? 500 : 204;
            exchange.sendResponseHeaders(code, -1);
            exchange.close();
        });
        agent.start();
    }

    @AfterAll
    static void stopAgent() {
        agent.stop(0);
    }

    @BeforeEach
    void inboxAgent() {
        jdbc.update("""
                INSERT INTO agent (name, display_name, kind, runtime, language, owner_org, owner_user, status, liveness)
                VALUES ('inbox-agent', '审批测试', 'AGENT', 'code', 'Java', '研发效能组', 'dev', 'ONLINE', 'k8s')
                ON CONFLICT (name) DO NOTHING
                """);
        jdbc.update("""
                INSERT INTO agent_version (agent_name, version, env, manifest_json, manifest_hash, released_by)
                VALUES ('inbox-agent', 'v-test', 'dev', ?::jsonb, 'test', 'test')
                ON CONFLICT (agent_name, env, version) DO UPDATE SET manifest_json = EXCLUDED.manifest_json
                """, "{\"spec\":{\"runtime\":{\"endpoint\":\"http://127.0.0.1:" + agent.getAddress().getPort() + "\"}}}");
    }

    @Test void approvesATicketOnceAndKeepsTheOverviewCount() throws Exception {
        var ref = id("export");
        var created = mvc.perform(post("/api/v1/approvals")
                        .contentType("application/json")
                        .content("""
                                {"subjectType":"data.export","subjectRef":"%s","summary":"导出近 7 天审计","actorUser":"amy","comment":"ignored"}
                                """.formatted(ref)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.risk").value("HIGH"))
                .andExpect(jsonPath("$.data.tool").value(org.hamcrest.Matchers.nullValue()))
                .andReturn();
        var approvalId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.data.id").toString();
        mvc.perform(get("/api/v1/approvals").param("status", "PENDING").param("size", "100"))
                .andExpect(jsonPath("$.data.items[*].id", hasItem(approvalId)));
        var pending = jdbc.queryForObject("SELECT count(*) FROM approval_request WHERE status = 'PENDING'", Long.class).intValue();
        mvc.perform(get("/api/v1/insight/overview"))
                .andExpect(jsonPath("$.data.kpi.pendingApprovals").value(pending));
        mvc.perform(post("/api/v1/approvals/" + approvalId + "/decision")
                        .contentType("application/json")
                        .content("{\"decision\":\"APPROVE\",\"comment\":\"可以\"}"))
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.decidedBy").value("dev"));
        mvc.perform(post("/api/v1/approvals/" + approvalId + "/decision")
                        .contentType("application/json")
                        .content("{\"decision\":\"REJECT\"}"))
                .andExpect(status().is(408))
                .andExpect(jsonPath("$.code").value("APPROVAL_EXPIRED"));
        mvc.perform(get("/api/v1/approvals").param("status", "PENDING").param("size", "100"))
                .andExpect(jsonPath("$.data.items[*].id", not(hasItem(approvalId))));
        var audited = jdbc.queryForObject(
                "SELECT count(*) FROM console_audit_event WHERE action = 'approval' AND resource = ?", Long.class, ref);
        org.assertj.core.api.Assertions.assertThat(audited).isEqualTo(2L);
    }

    @Test void rejectsUnknownInputAndASecondDecisionAfterReject() throws Exception {
        mvc.perform(post("/api/v1/approvals").contentType("application/json").content("{\"subjectType\":\"nope\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SERVER_INVALID_PARAM"));
        mvc.perform(get("/api/v1/approvals").param("size", "7"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/v1/approvals").param("status", "BROKEN").param("size", "10"))
                .andExpect(status().isBadRequest());
        var created = open("tool.call", id("tool"), "inbox-agent");
        mvc.perform(post("/api/v1/approvals/" + created + "/decision")
                        .contentType("application/json")
                        .content("{\"decision\":\"REJECT\"}"))
                .andExpect(jsonPath("$.data.status").value("REJECTED"))
                .andExpect(jsonPath("$.data.tool").value(org.hamcrest.Matchers.notNullValue()));
        mvc.perform(post("/api/v1/approvals/nope/decision")
                        .contentType("application/json")
                        .content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isNotFound());
    }

    @Test void expiresADueTicketAndReusesAnApprovalInsideCooldown() throws Exception {
        jdbc.update("""
                INSERT INTO approval_policy (name, subject_type, approver_type, approver_ref, timeout_minutes, cooldown_hours)
                VALUES ('导出冷却', 'data.export', 'ROLE', 'ADMIN', 60, 24)
                """);
        var ref = id("cool");
        var first = mvc.perform(post("/api/v1/approvals").contentType("application/json").content("""
                {"subjectType":"data.export","subjectRef":"%s","summary":"第一次导出","actorUser":"amy","policyName":"导出冷却"}
                """.formatted(ref))).andReturn();
        var approvalId = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.data.id").toString();
        mvc.perform(post("/api/v1/approvals/" + approvalId + "/decision")
                        .contentType("application/json").content("{\"decision\":\"APPROVE\"}"))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        mvc.perform(post("/api/v1/approvals").contentType("application/json").content("""
                {"subjectType":"data.export","subjectRef":"%s","summary":"冷却期内再导出","actorUser":"amy","policyName":"导出冷却"}
                """.formatted(ref)))
                .andExpect(jsonPath("$.data.id").value(approvalId))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        var other = id("other");
        mvc.perform(post("/api/v1/approvals").contentType("application/json").content("""
                {"subjectType":"data.export","subjectRef":"%s","summary":"另一份导出","actorUser":"amy","policyName":"导出冷却"}
                """.formatted(other)))
                .andExpect(jsonPath("$.data.status").value("PENDING"));
        var due = open("agent.config", id("due"), null);
        jdbc.update("UPDATE approval_request SET expires_at = now() - interval '1 minute' WHERE id = ?", numeric(due));
        mvc.perform(post("/api/v1/approvals/" + due + "/decision")
                        .contentType("application/json").content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().is(408))
                .andExpect(jsonPath("$.code").value("APPROVAL_EXPIRED"));
        var status = jdbc.queryForObject("SELECT status FROM approval_request WHERE id = ?", String.class, numeric(due));
        org.assertj.core.api.Assertions.assertThat(status).isEqualTo("EXPIRED");
        mvc.perform(post("/api/v1/approvals").contentType("application/json").content("""
                {"subjectType":"data.export","subjectRef":"missing-policy","summary":"没有这张策略","actorUser":"amy","policyName":"不存在"}
                """)).andExpect(status().isNotFound());
    }

    @Test void resumesAFreshRunAndRefusesAStaleOne() throws Exception {
        var run = id("run");
        mvc.perform(post("/api/v1/runs").contentType("application/json").content("""
                {"runId":"%s","agent":"inbox-agent","reason":"input_required","prompt":"先修哪一台？","actorUser":"张工"}
                """.formatted(run)))
                .andExpect(jsonPath("$.data.reason").value("input_required"))
                .andExpect(jsonPath("$.data.prompt").value("先修哪一台？"));
        mvc.perform(post("/api/v1/runs").contentType("application/json").content("""
                {"runId":"%s","agent":"inbox-agent","reason":"input_required","prompt":"再问一次","actorUser":"张工"}
                """.formatted(run)))
                .andExpect(jsonPath("$.data.prompt").value("先修哪一台？"));
        mvc.perform(get("/api/v1/runs").param("size", "100"))
                .andExpect(jsonPath("$.data.items[*].runId", hasItem(run)));
        RESUMES.clear();
        mvc.perform(post("/api/v1/runs/" + run + "/input").contentType("application/json").content("{\"text\":\"先修 WT-07\"}"))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(RESUMES).anySatisfy(line -> org.assertj.core.api.Assertions.assertThat(line).contains("先修 WT-07"));
        mvc.perform(post("/api/v1/runs/" + run + "/input").contentType("application/json").content("{\"text\":\"again\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RUN_NOT_RESUMABLE"));
        mvc.perform(post("/api/v1/runs").contentType("application/json").content("""
                {"runId":"%s","agent":"inbox-agent","reason":"input_required","prompt":"已经结束","actorUser":"张工"}
                """.formatted(run)))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/runs/" + run + "/input").contentType("application/json").content("{\"text\":\"\"}"))
                .andExpect(status().isBadRequest());

        var late = id("late");
        mvc.perform(post("/api/v1/runs").contentType("application/json").content("""
                {"runId":"%s","agent":"inbox-agent","reason":"handoff","prompt":"请接管","actorUser":"amy"}
                """.formatted(late))).andExpect(status().isOk());
        jdbc.update("UPDATE agent_run SET created_at = now() - interval '11 minutes' WHERE run_id = ?", late);
        mvc.perform(post("/api/v1/runs/" + late + "/input").contentType("application/json").content("{\"text\":\"我来\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("RUN_RESUME_DENIED"));
        org.assertj.core.api.Assertions.assertThat(runStatus(late)).isEqualTo("SUSPENDED");

        var delegated = id("delegated");
        mvc.perform(post("/api/v1/runs").contentType("application/json").content("""
                {"runId":"%s","agent":"inbox-agent","reason":"input_required","prompt":"等几天","actorUser":"amy","consentId":"consent_job"}
                """.formatted(delegated))).andExpect(status().isOk());
        jdbc.update("UPDATE agent_run SET created_at = now() - interval '11 minutes' WHERE run_id = ?", delegated);
        RESUMES.clear();
        mvc.perform(post("/api/v1/runs/" + delegated + "/input").contentType("application/json").content("{\"text\":\"继续\"}"))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(RESUMES).anySatisfy(line ->
                org.assertj.core.api.Assertions.assertThat(line).contains("consent_job"));

        var expired = id("deadline");
        mvc.perform(post("/api/v1/runs").contentType("application/json").content("""
                {"runId":"%s","agent":"inbox-agent","reason":"input_required","prompt":"已经过期","actorUser":"amy","deadline":"2020-01-01T00:00:00Z"}
                """.formatted(expired))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/runs/" + expired + "/input").contentType("application/json").content("{\"text\":\"晚了\"}"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("RUN_EXPIRED"));
        org.assertj.core.api.Assertions.assertThat(runStatus(expired)).isEqualTo("EXPIRED");
    }

    @Test void approvalWithARunCallsResumeAndAStaleRunStaysPending() throws Exception {
        var fresh = id("fresh");
        suspend(fresh, 0);
        var approval = open("tool.call", fresh, "inbox-agent", fresh);
        RESUMES.clear();
        mvc.perform(post("/api/v1/approvals/" + approval + "/decision")
                        .contentType("application/json").content("{\"decision\":\"APPROVE\"}"))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        org.assertj.core.api.Assertions.assertThat(RESUMES).anySatisfy(line ->
                org.assertj.core.api.Assertions.assertThat(line).contains(fresh).contains("approve"));
        org.assertj.core.api.Assertions.assertThat(runStatus(fresh)).isEqualTo("RUNNING");

        var old = id("old");
        suspend(old, 11);
        var waiting = open("tool.call", old, "inbox-agent", old);
        mvc.perform(post("/api/v1/approvals/" + waiting + "/decision")
                        .contentType("application/json").content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("RUN_RESUME_DENIED"));
        var still = jdbc.queryForObject("SELECT status FROM approval_request WHERE id = ?", String.class, numeric(waiting));
        org.assertj.core.api.Assertions.assertThat(still).isEqualTo("PENDING");

        var granted = id("granted");
        suspend(granted, 11);
        jdbc.update("UPDATE agent_run SET consent_id = 'consent_release' WHERE run_id = ?", granted);
        var lateApproval = open("tool.call", granted, "inbox-agent", granted);
        RESUMES.clear();
        mvc.perform(post("/api/v1/approvals/" + lateApproval + "/decision")
                        .contentType("application/json").content("{\"decision\":\"APPROVE\"}"))
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        org.assertj.core.api.Assertions.assertThat(RESUMES).anySatisfy(line ->
                org.assertj.core.api.Assertions.assertThat(line).contains("consent_release"));
        mvc.perform(get("/api/v1/runs").param("size", "100"))
                .andExpect(jsonPath("$.data.items[*].runId", not(hasItem(old))));

        var down = id("down");
        suspend("down-run", 0);
        var blocked = open("tool.call", down, "inbox-agent", "down-run");
        mvc.perform(post("/api/v1/approvals/" + blocked + "/decision")
                        .contentType("application/json").content("{\"decision\":\"APPROVE\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("GW_AGENT_OFFLINE"));
        org.assertj.core.api.Assertions.assertThat(jdbc.queryForObject(
                "SELECT status FROM approval_request WHERE id = ?", String.class, numeric(blocked))).isEqualTo("PENDING");

        mvc.perform(post("/api/v1/runs").contentType("application/json")
                        .content("{\"runId\":\"bad id\",\"agent\":\"missing\",\"reason\":\"input_required\",\"prompt\":\"x\",\"actorUser\":\"amy\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/runs").contentType("application/json")
                        .content("{\"runId\":\"missing-agent\",\"agent\":\"nobody\",\"reason\":\"handoff\",\"prompt\":\"x\",\"actorUser\":\"amy\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/runs/missing-run/input").contentType("application/json").content("{\"text\":\"hi\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("RUN_NOT_FOUND"));
    }

    @Test void listsApprovalsAndRunsForOneEnvironment() throws Exception {
        var devRef = id("dev-ticket");
        var stagingRef = id("staging-ticket");
        mvc.perform(post("/api/v1/approvals").contentType("application/json").content("""
                {"subjectType":"data.export","subjectRef":"%s","summary":"dev 导出","actorUser":"amy","env":"dev"}
                """.formatted(devRef))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/approvals").contentType("application/json").content("""
                {"subjectType":"data.export","subjectRef":"%s","summary":"staging 导出","actorUser":"amy","env":"staging"}
                """.formatted(stagingRef))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/approvals").param("env", "dev").param("size", "100"))
                .andExpect(jsonPath("$.data.items[*].subjectRef", hasItem(devRef)))
                .andExpect(jsonPath("$.data.items[*].subjectRef", not(hasItem(stagingRef))));
        mvc.perform(get("/api/v1/insight/overview").param("env", "dev"))
                .andExpect(jsonPath("$.data.kpi.pendingApprovals").value(
                        jdbc.queryForObject("SELECT count(*) FROM approval_request WHERE status = 'PENDING' AND env = 'dev'", Long.class)));

        var devRun = id("dev-run");
        var testRun = id("test-run");
        mvc.perform(post("/api/v1/runs").contentType("application/json").content("""
                {"runId":"%s","agent":"inbox-agent","env":"dev","reason":"input_required","prompt":"dev 里等人","actorUser":"amy"}
                """.formatted(devRun))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/runs").contentType("application/json").content("""
                {"runId":"%s","agent":"inbox-agent","env":"test","reason":"handoff","prompt":"test 里转人工","actorUser":"amy"}
                """.formatted(testRun))).andExpect(status().isOk());
        mvc.perform(get("/api/v1/runs").param("env", "test").param("size", "100"))
                .andExpect(jsonPath("$.data.items[*].runId", hasItem(testRun)))
                .andExpect(jsonPath("$.data.items[*].runId", not(hasItem(devRun))));
        mvc.perform(get("/api/v1/approvals").param("env", "nope"))
                .andExpect(status().isBadRequest());
    }

    private String open(String subject, String ref, String agent) throws Exception {
        return open(subject, ref, agent, null);
    }

    private String open(String subject, String ref, String agent, String runId) throws Exception {
        var agentJson = agent == null ? "" : ",\"agent\":\"" + agent + "\"";
        var runJson = runId == null ? "" : ",\"runId\":\"" + runId + "\"";
        var created = mvc.perform(post("/api/v1/approvals").contentType("application/json").content("""
                {"subjectType":"%s","subjectRef":"%s","summary":"待办 %s","actorUser":"amy","risk":"mid"%s%s}
                """.formatted(subject, ref, ref, agentJson, runJson)))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.data.id");
    }

    private void suspend(String runId, int minutes) {
        jdbc.update("""
                INSERT INTO agent_run (run_id, agent_name, env, trace_id, status, suspend_reason, actor_user, created_at, resume_token)
                VALUES (?, 'inbox-agent', 'dev', 'trace', 'SUSPENDED', 'approval', 'amy', now() - make_interval(mins => ?), 'rt_test')
                """, runId, minutes);
    }

    private String runStatus(String runId) {
        return jdbc.queryForObject("SELECT status FROM agent_run WHERE run_id = ?", String.class, runId);
    }

    private static long numeric(String publicId) {
        return Long.parseLong(publicId.substring(3));
    }

    private static String id(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
