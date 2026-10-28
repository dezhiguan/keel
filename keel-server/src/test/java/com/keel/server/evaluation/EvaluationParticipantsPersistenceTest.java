package com.keel.server.evaluation;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.PostgresTestConfig;
import com.keel.server.auth.*;
import com.keel.server.common.KeelException;
import com.keel.server.registry.service.DebugSessionService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.context.request.*;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest @Import(PostgresTestConfig.class) @Testcontainers(disabledWithoutDocker=true)
class EvaluationParticipantsPersistenceTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired DebugSessionService sessions;
    private final String run="ev_"+"e".repeat(32);
    private EvaluationParticipants participants;
    private ConsolePrincipal a,b;
    private void current(ConsolePrincipal user){var req=new MockHttpServletRequest();ConsolePrincipal.set(req,user);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(req));}
    @BeforeEach void setup() throws Exception {
        var auth=new ConsoleAuthProperties();auth.getAuth().setMode("auth-gateway");
        var users=new ConsoleUsers("owner","[{\"username\":\"reader\",\"displayName\":\"QA\",\"platformRole\":\"VIEWER\",\"org\":\"平台组\"}]");
        participants=new EvaluationParticipants(jdbc,json,sessions,users,auth);
        a=new ConsolePrincipal("USER","user-a","owner","A","平台组","ADMIN",List.of(),false);
        b=new ConsolePrincipal("USER","user-b","reader","B","平台组","VIEWER",List.of(),false);
        jdbc.update("INSERT INTO eval_run(run_id,agent_name,env,namespace,source_pod,image,endpoint,dataset,requested_by,runner,context_user) VALUES (?,'keel-assistant','staging','agents','pod','image','http://agent','suite','owner','acceptance-v1',?::jsonb)",run,json.writeValueAsString(a));
        jdbc.update("INSERT INTO agent_employee_read_scope(agent_name,environment,target_org,enabled,granted_by) VALUES ('keel-assistant','staging','平台组',true,'unit-fixture') ON CONFLICT (agent_name,environment,target_org) DO UPDATE SET enabled=true");
        participants.initialize(run,"keel-assistant","staging",a);
    }
    @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();}
    @Test @Transactional void onlyIndependentUserCanConsentAndReplayDoesNotReplaceSession(){
        current(a);assertThatThrownBy(()->participants.consent(run)).isInstanceOf(KeelException.class);
        current(ConsolePrincipal.service("keel-assistant"));assertThatThrownBy(()->participants.consent(run)).isInstanceOf(KeelException.class);
        current(ConsolePrincipal.preview());assertThatThrownBy(()->participants.consent(run)).isInstanceOf(KeelException.class);
        assertThat(participants.context(run,1)).isNull();
        current(b);var proof=participants.consent(run);var again=participants.consent(run);
        assertThat(again.authorizationId()).isEqualTo(proof.authorizationId());
        assertThat(again.sessionId()).isEqualTo(proof.sessionId());
        assertThat(participants.context(run,0).evidence().ownerFingerprint()).isNotEqualTo(proof.ownerFingerprint());
        assertThat(participants.context(run,1).user().userId()).isEqualTo("user-b");
    }
    @Test @Transactional void actualPgTurnsRemainOwnedAndBDoesNotReceiveAHistory() throws Exception {
        current(b);participants.consent(run);
        var first=participants.context(run,0);assertThat(first.history()).isEmpty();
        var proof=participants.store(run,"case-a",0,"A question","A answer","a".repeat(32));
        assertThat(proof.storage().messageCount()).isEqualTo(2);
        jdbc.update("INSERT INTO eval_turn(run_id,case_id,turn_index,session_group,question,answer,evidence) VALUES (?,'case-a',0,'same','A question','A answer','{}'::jsonb)",run);
        assertThat(participants.store(run,"case-a",0,"A question","A answer","a".repeat(32)).storage().messageCount()).isEqualTo(2);
        var second=participants.context(run,1);assertThat(second.history()).isEmpty();
        assertThat(second.pgSession()).isNotEqualTo(first.pgSession());
        assertThatThrownBy(()->sessions.require("keel-assistant","staging",b.userId(),first.pgSession())).isInstanceOf(KeelException.class);
        assertThatThrownBy(()->participants.store(run,"case-a",0,"changed question","A answer","a".repeat(32))).isInstanceOf(KeelException.class);
        jdbc.update("UPDATE eval_run SET state='DONE' WHERE run_id=?",run);
        assertThatThrownBy(()->participants.context(run,1)).isInstanceOf(KeelException.class);
        assertThatThrownBy(()->participants.consent(run)).isInstanceOf(KeelException.class);
    }
    @Test @Transactional void disabledReadScopeCannotBeSelfGranted(){
        current(b);
        jdbc.update("UPDATE agent_employee_read_scope SET enabled=false WHERE agent_name='keel-assistant' AND environment='staging' AND target_org='平台组'");
        assertThatThrownBy(()->participants.consent(run)).isInstanceOf(KeelException.class);
        assertThatThrownBy(()->participants.context(run,0)).isInstanceOf(KeelException.class);
    }
    @Test @Transactional void authorizationCannotCrossUnknownRunOrExpiredGrant(){
        current(b);assertThatThrownBy(()->participants.consent("ev_"+"f".repeat(32))).isInstanceOf(KeelException.class);
        participants.consent(run);
        jdbc.update("UPDATE eval_run_participant SET expires_at=now()-interval '1 second' WHERE run_id=? AND participant_index=1",run);
        assertThatThrownBy(()->participants.context(run,1)).isInstanceOf(KeelException.class);
        assertThatThrownBy(()->participants.requireComplete(run,"case-a")).isInstanceOf(KeelException.class);
    }
    @Test @Transactional void serverOnlyAcceptsBothStoredIdentityReceipts() throws Exception {
        current(b);participants.consent(run);
        for(int turn=0;turn<2;turn++) {
            var proof=participants.store(run,"case-a",turn,"question-"+turn,"answer-"+turn,String.valueOf(turn+1).repeat(32));
            var evidence=json.createObjectNode();evidence.putObject("sessionEvidence").set("participant",json.valueToTree(proof));
            jdbc.update("INSERT INTO eval_turn(run_id,case_id,turn_index,session_group,question,answer,evidence) VALUES (?,'case-a',?,'same',?,?,?::jsonb)",
                    run,turn,"question-"+turn,"answer-"+turn,evidence.toString());
        }
        assertThatCode(()->participants.requireComplete(run,"case-a")).doesNotThrowAnyException();
        jdbc.update("UPDATE eval_turn SET evidence='{}'::jsonb WHERE run_id=? AND case_id='case-a' AND turn_index=1",run);
        assertThatThrownBy(()->participants.requireComplete(run,"case-a")).isInstanceOf(KeelException.class);
    }
}
