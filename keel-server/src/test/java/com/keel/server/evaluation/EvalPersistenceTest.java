package com.keel.server.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.PostgresTestConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(PostgresTestConfig.class)
@Testcontainers(disabledWithoutDocker=true)
class EvalPersistenceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired com.keel.server.registry.mapper.AgentMapper agents;
    @Autowired com.keel.server.registry.mapper.AgentVersionMapper versions;
    @Autowired com.keel.server.registry.mapper.AgentInstanceMapper instances;
    @Autowired com.keel.server.provisioning.ResourceLedger ledger;

    @Test @Transactional void anotherServiceInstanceReadsTheSameCompletedRunAndFailure() {
        jdbc.update("""
            INSERT INTO eval_run(run_id,agent_name,env,namespace,source_pod,image,endpoint,dataset,requested_by,state,result)
            VALUES ('ev_persist','qa-persist','dev','agents','pod','image','http://agent','qa/smoke','operator','DONE','{"scoreTotal":0.5,"passed":false}')
            """);
        var first=new EvalJobs(jdbc,json,null,null,null);
        var restarted=new EvalJobs(jdbc,json,null,null,null);
        assertThat(first.run("ev_persist")).isEqualTo(restarted.run("ev_persist"));
        assertThat(restarted.latest("qa-persist")).containsEntry("passed",false);
        jdbc.update("UPDATE eval_run SET state='FAILED',error='scorer missing',result=null WHERE run_id='ev_persist'");
        assertThat(restarted.run("ev_persist")).containsEntry("error","scorer missing").containsEntry("state","FAILED");
    }

    @Test @Transactional void activeRunSurvivesServiceRestartAndDisappearsAfterCompletion() {
        jdbc.update("""
            INSERT INTO eval_run(run_id,agent_name,env,namespace,source_pod,image,endpoint,dataset,requested_by)
            VALUES ('ev_recover','qa-recover','dev','agents','pod','image','http://agent','qa/smoke','operator')
            """);
        var restarted = new EvalJobs(jdbc,json,null,null,null);
        assertThat(restarted.active("qa-recover")).containsEntry("runId", "ev_recover");
        assertThat(restarted.active("another-agent")).isNull();
        jdbc.update("UPDATE eval_run SET state='RUNNING' WHERE run_id='ev_recover'");
        assertThat(restarted.active("qa-recover")).containsEntry("runId", "ev_recover");
        jdbc.update("UPDATE eval_run SET state='DONE' WHERE run_id='ev_recover'");
        assertThat(restarted.active("qa-recover")).isNull();
        assertThat(restarted.active("qa-recover", true)).isNull();
        jdbc.update("UPDATE eval_run SET state='FAILED',error='incomplete' WHERE run_id='ev_recover'");
        assertThat(restarted.active("qa-recover")).isNull();
        assertThat(restarted.active("qa-recover", true)).containsEntry("runId", "ev_recover");
        jdbc.update("""
            INSERT INTO eval_run(run_id,agent_name,env,namespace,source_pod,image,endpoint,dataset,requested_by,state,created_at)
            VALUES ('ev_recover_new','qa-recover','dev','agents','pod','image','http://agent','qa/smoke','operator','DONE',now()+interval '1 second')
            """);
        assertThat(restarted.active("qa-recover", true)).isNull();
    }

    @Test @Transactional void duplicateConcurrentRunIsRejectedByDatabase() {
        jdbc.update("""
            INSERT INTO eval_run(run_id,agent_name,env,namespace,source_pod,image,endpoint,dataset,requested_by)
            VALUES ('ev_unique','qa-unique','dev','agents','pod','image','http://agent','qa/smoke','operator')
            """);
        assertThatThrownBy(()->jdbc.update("""
            INSERT INTO eval_run(run_id,agent_name,env,namespace,source_pod,image,endpoint,dataset,requested_by)
            VALUES ('ev_duplicate','qa-unique','dev','agents','pod','image','http://agent','qa/smoke','operator')
            """)).isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test @Transactional void baselineFollowsPublishedEvidenceIncludingDevAndIgnoresUnpublishedRuns() throws Exception {
        seedBaselineAgent();
        var jobs = new EvalJobs(jdbc,json,null,null,null);
        assertThat(json.readTree(jobs.baseline("qa-baseline"))).isEmpty();
        baselineRun("ev_base_dev", "dev", 1.0);
        baselineRelease("ev_base_dev", "image", 1);
        baselineRun("ev_base_unpublished", "prod", 0.2);
        jdbc.update("UPDATE eval_run SET result=jsonb_set(result,'{passed}','false') WHERE run_id='ev_base_unpublished'");
        assertThat(json.readTree(jobs.baseline("qa-baseline")).path("tool").asDouble()).isEqualTo(1.0);
        jdbc.update("UPDATE eval_run SET result=jsonb_set(jsonb_set(result,'{passed}','true'),'{dimensions,0,candidateScore}','0.98') WHERE run_id='ev_base_unpublished'");
        baselineRelease("ev_base_unpublished", "image", 2);
        assertThat(json.readTree(jobs.baseline("qa-baseline")).path("tool").asDouble()).isEqualTo(0.98);
        baselineRelease("legacy-ci-url", "image", 3);
        assertThat(json.readTree(jobs.baseline("qa-baseline"))).isEmpty();
        baselineRelease("ev_base_missing", "image", 4);
        assertThatThrownBy(() -> jobs.baseline("qa-baseline")).isInstanceOf(IllegalStateException.class);
    }

    @Test @Transactional void invalidPublishedEvidenceRefusesAReplacementBaseline() {
        seedBaselineAgent();
        var jobs = new EvalJobs(jdbc,json,null,null,null);
        baselineRun("ev_base_invalid", "dev", 1.0);
        baselineRelease("ev_base_invalid", "wrong-image", 1);
        assertThatThrownBy(() -> jobs.baseline("qa-baseline")).isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE release_record SET image='image' WHERE agent_name='qa-baseline'");
        jdbc.update("UPDATE eval_run SET result=jsonb_set(result,'{passed}','false') WHERE run_id='ev_base_invalid'");
        assertThatThrownBy(() -> jobs.baseline("qa-baseline")).isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE eval_run SET result=jsonb_set(jsonb_set(result,'{passed}','true'),'{complete}','false') WHERE run_id='ev_base_invalid'");
        assertThatThrownBy(() -> jobs.baseline("qa-baseline")).isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE eval_run SET agent_name='another-agent' WHERE run_id='ev_base_invalid'");
        assertThatThrownBy(() -> jobs.baseline("qa-baseline")).isInstanceOf(IllegalStateException.class);
    }

    private void seedBaselineAgent() {
        jdbc.update("""
            INSERT INTO agent(name,display_name,kind,runtime,language,owner_org,owner_user,status,liveness)
            VALUES ('qa-baseline','baseline','AGENT','code','python','平台','dev','ONLINE','k8s')
            """);
    }

    @Test @Transactional void registeredControlDataDoesNotWaitForExternalObservationEvidence() {
        seedBaselineAgent();
        jdbc.update("""
            INSERT INTO agent_version(agent_name,version,env,manifest_json,manifest_hash,released_by)
            VALUES ('qa-baseline','v1','prod','{"spec":{"eval":{"dataset":"qa-baseline/smoke"}}}','hash','test')
            """);
        ledger.activate("qa-baseline", "prod", "litellm_key", "qa-baseline-prod");
        var registry = new com.keel.server.registry.service.AgentRegistryService(agents,versions,instances,ledger,json,null);
        var unavailable = new com.keel.server.registry.service.ElementEvidence(jdbc,null) {
            @Override public com.keel.server.registry.model.dto.AgentDetail apply(com.keel.server.registry.model.dto.AgentDetail detail) {
                throw new IllegalStateException("external observation unavailable");
            }
        };
        org.springframework.test.util.ReflectionTestUtils.setField(registry,"elementEvidence",unavailable);
        var detail = registry.registeredDetail("qa-baseline");
        assertThat(detail.summary().env()).isEqualTo("prod");
        assertThat(detail.summary().language()).isEqualTo("python");
        assertThat(detail.resources()).hasSize(1);
        assertThat(detail.resources().getFirst().status()).isEqualTo("ACTIVE");
        assertThatThrownBy(() -> registry.detail("qa-baseline")).isInstanceOf(IllegalStateException.class)
                .hasMessage("external observation unavailable");
    }

    private void baselineRun(String id, String env, double score) {
        jdbc.update("""
            INSERT INTO eval_run(run_id,agent_name,env,namespace,source_pod,image,endpoint,dataset,requested_by,state,result)
            VALUES (?,'qa-baseline',?,'agents','pod','image','http://agent','qa/smoke','operator','DONE',?::jsonb)
            """, id, env, "{\"complete\":true,\"passed\":true,\"dimensions\":[{\"tag\":\"tool\",\"candidateScore\":" + score + "}]}");
    }

    private void baselineRelease(String runId, String image, int order) {
        jdbc.update("""
            INSERT INTO release_record(agent_name,version,env,gate_passed,ci_run_url,image,kind,created_at)
            VALUES ('qa-baseline','v1','prod',true,?,?,'release',now()+(? * interval '1 second'))
            """, runId, image, order);
    }
}
