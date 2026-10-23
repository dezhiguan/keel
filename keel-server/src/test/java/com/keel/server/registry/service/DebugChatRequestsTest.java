package com.keel.server.registry.service;

import com.keel.server.PostgresTestConfig;
import com.keel.server.common.KeelException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest
@Import(PostgresTestConfig.class)
@Testcontainers(disabledWithoutDocker=true)
class DebugChatRequestsTest {
    @Autowired JdbcTemplate jdbc;
    @BeforeEach void clear() { jdbc.update("DELETE FROM debug_chat_request WHERE agent_name='request-test'"); }
    @Test void replicasRaceForOnePaidExecutionAndThenReplayTheSameTerminal() throws Exception {
        var winners=new AtomicInteger(); var start=new CountDownLatch(1);
        var claims=new java.util.concurrent.ConcurrentLinkedQueue<DebugChatRequests.Claim>();
        try(var workers=Executors.newFixedThreadPool(8)) {
            var futures=new java.util.ArrayList<Future<?>>();
            for(int i=0;i<8;i++) futures.add(workers.submit(()->{
                try { start.await(); claims.add(new DebugChatRequests(jdbc).begin("request-test","staging","u1","s1","r1","31+12")); winners.incrementAndGet(); }
                catch(KeelException expected) { assertThat(expected.getMessage()).contains("原请求"); }
                catch(InterruptedException e) { throw new RuntimeException(e); }
            }));
            start.countDown(); for(var future:futures) future.get(10,TimeUnit.SECONDS);
        }
        assertThat(winners).hasValue(1);
        var frame="event: final\ndata: {\"answer\":\"43\",\"trace_id\":\"real-trace\"}\n\n";
        new DebugChatRequests(jdbc).finish(claims.element(),frame);
        assertThat(new DebugChatRequests(jdbc).begin("request-test","staging","u1","s1","r1","31+12").replay()).isEqualTo(frame);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM debug_chat_request WHERE agent_name='request-test'",Integer.class)).isEqualTo(1);
    }
    @Test void conflictsAndUnknownOutcomesCannotBeReexecutedAndScopesAreIndependent() {
        var requests=new DebugChatRequests(jdbc);
        var claim=requests.begin("request-test","staging","u1","s1","r1","first"); requests.finish(claim,null);
        assertThatThrownBy(()->requests.begin("request-test","staging","u1","s1","r1","first")).isInstanceOf(KeelException.class).hasMessageContaining("结果未知");
        assertThatThrownBy(()->requests.begin("request-test","staging","u1","s1","r1","changed")).isInstanceOf(KeelException.class).hasMessageContaining("输入内容不同");
        assertThat(requests.begin("request-test","staging","u2","s1","r1","first").replay()).isNull();
        assertThat(requests.begin("request-test","prod","u1","s1","r1","first").replay()).isNull();
        assertThat(requests.begin("request-test","staging","u1","s2","r1","first").replay()).isNull();
    }

    @Test void crashedWorkersBecomeUnknownWithoutRecomputingAndCanReconcileTheirLateTerminal() {
        var original=new DebugChatRequests(jdbc);
        var claim=original.begin("request-test","staging","u1","s1","orphan","paid request");
        jdbc.update("UPDATE debug_chat_request SET created_at=now()-interval '6 minutes' WHERE agent_name='request-test'");
        var restarted=new DebugChatRequests(jdbc);
        assertThatThrownBy(()->restarted.begin("request-test","staging","u1","s1","orphan","paid request"))
                .isInstanceOf(KeelException.class).hasMessageContaining("结果未知");
        assertThat(jdbc.queryForObject("SELECT state FROM debug_chat_request WHERE agent_name='request-test'",String.class)).isEqualTo("UNKNOWN");
        var frame="event: final\ndata: {\"answer\":\"original paid result\",\"trace_id\":\"original\"}\n\n";
        original.finish(claim,frame);
        original.finish(claim,null); // Cancellation cleanup cannot downgrade a reconciled receipt.
        assertThat(restarted.begin("request-test","staging","u1","s1","orphan","paid request").replay()).isEqualTo(frame);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM debug_chat_request WHERE agent_name='request-test'",Integer.class)).isEqualTo(1);
    }
}
