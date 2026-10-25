package com.keel.server.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.auth.UserReadContext;
import com.keel.server.common.KeelException;
import com.keel.server.registry.service.DebugSessionService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import static org.assertj.core.api.Assertions.*;
import java.util.*;

@SpringBootTest
@Import(com.keel.server.PostgresTestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
class CrossSessionHistoryFlowTest {
    @Autowired JdbcTemplate db;
    @Autowired DebugSessionService sessions;
    final ObjectMapper json=new ObjectMapper();
    CrossSessionHistory history;
    String current,old,token;
    @BeforeEach void setup() {
        for(var table:List.of("session_message","agent_session"))db.update("DELETE FROM "+table+" WHERE agent_name='cross-test'");
        db.update("INSERT INTO agent_history_policy(agent_name,env,retention_days) VALUES('cross-test','staging',NULL) ON CONFLICT DO NOTHING");
        current=(String)sessions.create("cross-test","staging","u1").get("id");
        old=(String)sessions.create("cross-test","staging","u1").get("id");
        var contexts=new UserReadContext(new com.keel.server.auth.ConsoleSigningKey(),new com.keel.server.auth.ConsoleUsers("tester"));
        token=contexts.issue(new com.keel.server.auth.ConsolePrincipal("USER","u1","tester","测试","org","ADMIN",List.of(),false),"cross-test","staging",current);
        history=new CrossSessionHistory(db,contexts);
    }
    String hash(String session){return UserReadContext.sessionHash("u1",session);}
    Map<String,Object> search(String query,boolean exact) {
        return history.search("cross-test","staging",json.valueToTree(Map.of("query",query,"exact",exact,"_authorization",token,"_session",hash(current))));
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> items(Map<String,Object> result){return (List<Map<String,Object>>)result.get("items");}
    @Test void retrievesOriginalInAnotherWindowButExcludesCurrentOtherOwnerEnvironmentAndExpired() {
        sessions.appendTurn("cross-test","u1",old,"我的项目代号是 ORCHID-7419，你好啊。","收到","fixture");
        sessions.appendTurn("cross-test","u1",current,"我之前说过你好啊吗","你好啊","fixture");
        for(var pair:List.of(List.of("u2","staging"),List.of("u1","prod"))) {
            var id=(String)sessions.create("cross-test",pair.get(1),pair.get(0)).get("id");
            sessions.appendTurn("cross-test",pair.get(0),id,"你好啊 私密 OTHER-OWNER","你好啊","fixture");
        }
        var other=(String)sessions.create("other-cross-test","staging","u1").get("id");
        sessions.appendTurn("other-cross-test","u1",other,"你好啊 OTHER-AGENT","收到","fixture");
        var hits=items(search("你好啊",true));assertThat(hits).hasSize(1);
        assertThat(hits.getFirst()).containsEntry("sessionId",old).containsEntry("role","user");
        assertThat(hits.getFirst().get("url")).isEqualTo("/agents/cross-test/debug?session="+old);
        assertThat(items(search("项目代号",false))).hasSize(1);
        assertThat(items(search("NEVER-SAID-991",true))).isEmpty();
        db.update("UPDATE session_message SET expires_at=now()-interval '1 second' WHERE session_id=?",old);
        assertThat(items(search("你好啊",true))).isEmpty();
    }
    @Test void retentionSurvivesSevenDaysAndDeleteRemovesRetrieval() {
        sessions.appendTurn("cross-test","u1",old,"长期事实 LONG-7419","收到","fixture");
        assertThat(db.queryForObject("SELECT bool_and(expires_at='infinity'::timestamptz) FROM session_message WHERE session_id=?",Boolean.class,old)).isTrue();
        db.update("UPDATE agent_session SET updated_at=now()-interval '90 days' WHERE id=?",old);
        assertThat(sessions.list("cross-test","staging","u1",null)).containsEntry("total",2L).containsEntry("retention","保留至删除");
        assertThat(items(search("LONG-7419",true))).hasSize(1);
        sessions.delete("cross-test","u1",old);
        assertThat(items(search("LONG-7419",true))).isEmpty();
    }
    @Test void authorizationRejectsForgedOwnersAndCurrentSessionAndReadsFullUnicodeExcerpt() {
        sessions.appendTurn("cross-test","u1",old,"填".repeat(5000)+"🦉 UNICODE-7419 尾部","收到","fixture");
        var hit=items(search("UNICODE-7419",true)).getFirst();
        assertThat(hit.get("content").toString()).contains("🦉 UNICODE-7419");
        assertThat(hit.get("offset")).isEqualTo(4842);
        var args=json.valueToTree(Map.of("query","UNICODE-7419","_authorization",token,"_session",hash(current),"ownerUser","u2"));
        assertThatThrownBy(()->history.search("cross-test","staging",args)).isInstanceOf(KeelException.class);
        var mismatch=json.valueToTree(Map.of("query","UNICODE-7419","_authorization",token,"_session",hash(old)));
        assertThatThrownBy(()->history.search("cross-test","staging",mismatch)).isInstanceOf(KeelException.class);
    }
    @Test void repeatedQuestionsKeepAdjacentHistoricalRepliesWithoutMixingRolesOrGrowingBudget() {
        for(int i=0;i<8;i++) {
            var id=(String)sessions.create("cross-test","staging","u1").get("id");
            sessions.appendTurn("cross-test","u1",id,"松鹤项目的代号和日期是什么？","历史助理回复：ORCHID-7419，12月17日。"+"长".repeat(1800),"fixture");
        }
        var hits=items(search("松鹤项目的代号和日期是什么？",false));assertThat(hits).hasSize(4);
        for(var hit:hits) {
            assertThat(hit.get("role")).isEqualTo("user");
            var reply=(Map<?,?>)hit.get("followingMessage");
            assertThat(reply.get("role")).isEqualTo("assistant");
            assertThat(reply.get("content").toString()).contains("ORCHID-7419");
            assertThat(reply.get("sessionId")).isEqualTo(hit.get("sessionId"));
            assertThat(reply.get("hasMore")).isEqualTo(true);
            assertThat(hit.get("content").toString().codePointCount(0,hit.get("content").toString().length())
                +reply.get("content").toString().codePointCount(0,reply.get("content").toString().length())).isLessThanOrEqualTo(1200);
        }
        assertThat(items(search("松鹤项目",true))).allMatch(hit->!hit.containsKey("followingMessage"));
        var id=(String)hits.getFirst().get("sessionId");sessions.delete("cross-test","u1",id);
        assertThat(items(search("松鹤项目",false))).noneMatch(hit->id.equals(hit.get("sessionId")));
    }
    @Test void contextDoesNotSkipAnInterveningUserMessageOrReadOtherOwners() {
        db.update("INSERT INTO session_message(session_id,agent_name,owner_user,role,content,expires_at) VALUES (?, 'cross-test','u1','user','NEIGHBOR-7419',now()+interval '1 day')",old);
        db.update("INSERT INTO session_message(session_id,agent_name,owner_user,role,content,expires_at) VALUES (?, 'cross-test','u1','user','next user',now()+interval '1 day')",old);
        db.update("INSERT INTO session_message(session_id,agent_name,owner_user,role,content,expires_at) VALUES (?, 'cross-test','u1','assistant','not the adjacent reply',now()+interval '1 day')",old);
        assertThat(items(search("NEIGHBOR-7419",false)).getFirst()).doesNotContainKey("followingMessage");
    }

}
