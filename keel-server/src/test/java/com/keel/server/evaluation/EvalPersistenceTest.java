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
