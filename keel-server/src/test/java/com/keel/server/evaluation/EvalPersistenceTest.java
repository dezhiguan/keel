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
}
