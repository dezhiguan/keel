package com.keel.server.registry.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.PostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(PostgresTestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class VersionEvidencePersistenceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired com.keel.server.registry.mapper.AgentMapper agents;
    @Autowired com.keel.server.registry.mapper.AgentVersionMapper versions;
    @Autowired com.keel.server.registry.mapper.AgentInstanceMapper instances;
    @Autowired com.keel.server.provisioning.ResourceLedger ledger;

    @Test @Transactional void versionRowsReadTheirOwnEvaluationAndIgnoreRollback() {
        jdbc.update("""
                INSERT INTO agent(name,display_name,kind,runtime,language,owner_org,owner_user,status,liveness)
                VALUES ('qa-version-evidence','证据','AGENT','code','python','平台','dev','ONLINE','k8s')
                """);
        insertVersion("v2", "prod", "ev_current", "2026-10-19 22:58:00+08");
        insertVersion("v1", "prod", null, "2026-10-19 22:11:00+08");
        insertVersion("v0", "dev", null, "2026-10-19 15:39:00+08");
        insertEval("ev_current", "qa-version-evidence", "{\"scoreTotal\":1,\"passed\":true}");
        insertEval("ev_old", "qa-version-evidence", "{\"scoreTotal\":0.8,\"passed\":false}");
        insertEval("ev_other", "someone-else", "{\"scoreTotal\":0.1,\"passed\":true}");
        jdbc.update("""
                INSERT INTO release_record(agent_name,version,env,gate_passed,score_total,ci_run_url,kind,created_at)
                VALUES ('qa-version-evidence','v1','prod',true,null,'ev_old','release','2026-10-19 22:11:00+08'),
                       ('qa-version-evidence','v1','prod',true,null,'因为回滚','rollback','2026-10-19 23:00:00+08')
                """);
        var registry = new AgentRegistryService(agents, versions, instances, ledger, json, null);
        ReflectionTestUtils.setField(registry, "versionEvidence", new VersionEvidence(jdbc, json));
        var rows = registry.registeredDetail("qa-version-evidence").versions();
        assertThat(rows).extracting(row -> row.version() + "/" + row.env() + ":" + row.scoreTotal() + ":" + row.gatePassed())
                .containsExactly("v2/prod:1.0:true", "v1/prod:0.8:false", "v0/dev:null:null");
    }

    @Test @Transactional void rollbackSelectsOriginalReleaseEvidenceAndNotAnotherSourceWithTheSameNumber() {
        jdbc.update("""
                INSERT INTO agent(name,display_name,kind,runtime,language,owner_org,owner_user,status,liveness)
                VALUES ('qa-version-evidence','证据','AGENT','code','python','平台','dev','ONLINE','k8s')
                """);
        insertVersion("v1", "dev", "ev_dev", "2026-10-18 00:00:00Z");
        insertVersion("v1", "test", "ev_test", "2026-10-19 00:00:00Z");
        jdbc.update("UPDATE agent_version SET image='image:dev' WHERE agent_name='qa-version-evidence' AND env='dev'");
        jdbc.update("UPDATE agent_version SET image='image:test' WHERE agent_name='qa-version-evidence' AND env='test'");
        jdbc.update("""
                INSERT INTO release_record(agent_name,version,env,gate_passed,ci_run_url,image,kind,created_at)
                VALUES ('qa-version-evidence','v1','prod',true,'ev_dev','image:dev','release','2026-10-18 01:00:00Z'),
                       ('qa-version-evidence','v2','prod',true,'ev_v2','image:v2','release','2026-10-19 01:00:00Z'),
                       ('qa-version-evidence','v1','prod',true,'rollback reason',null,'rollback','2026-10-20 01:00:00Z')
                """);
        var evidence = new VersionEvidence(jdbc,json);
        var rows = evidence.attach("qa-version-evidence",versions.listByAgent("qa-version-evidence"));
        assertThat(rows.stream().filter(row -> row.env().equals("dev")).findFirst().orElseThrow().publishedEnvs()).containsExactly("prod");
        assertThat(rows.stream().filter(row -> row.env().equals("test")).findFirst().orElseThrow().publishedEnvs()).isEmpty();
    }

    private void insertVersion(String version, String env, String runId, String at) {
        jdbc.update("""
                INSERT INTO agent_version(agent_name,version,env,manifest_json,manifest_hash,gate_run_id,released_by,released_at)
                VALUES ('qa-version-evidence',?,?,'{}','hash',?,'amy',?::timestamptz)
                """, version, env, runId, at);
    }

    private void insertEval(String id, String agent, String result) {
        jdbc.update("""
                INSERT INTO eval_run(run_id,agent_name,env,namespace,source_pod,image,endpoint,dataset,requested_by,state,result)
                VALUES (?,?,'dev','agents','pod','image','http://agent','qa/smoke','operator','DONE',?::jsonb)
                """, id, agent, result);
    }
}
