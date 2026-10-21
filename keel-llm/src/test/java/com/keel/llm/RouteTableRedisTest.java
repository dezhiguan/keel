package com.keel.llm;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import java.net.ServerSocket;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.*;
import static org.assertj.core.api.Assertions.*;

class RouteTableRedisTest {
    @Test void replicasShareDraftPublishRollbackAndRejectStaleRevision() throws Exception {
        int port;try(var socket=new ServerSocket(0)){port=socket.getLocalPort();}
        Process process;
        var dir=Files.createTempDirectory("keel-route-redis-");
        var log=dir.resolve("redis.log");
        try {var builder=new ProcessBuilder("redis-server","--bind","127.0.0.1","--port",Integer.toString(port),"--save","","--appendonly","no","--dir",dir.toString())
                .redirectErrorStream(true).redirectOutput(log.toFile());builder.environment().put("LC_ALL","C");builder.environment().put("LANG","C");process=builder.start();}
        catch(java.io.IOException unavailable){Assumptions.assumeTrue(false,"redis-server unavailable; other route tests use memory");return;}
        try {
            boolean ready=false;
            for(int i=0;i<200 && process.isAlive();i++) {
                try(var socket=new java.net.Socket()) { socket.connect(new java.net.InetSocketAddress("127.0.0.1",port),50);ready=true;break; }
                catch(java.io.IOException retry){Thread.sleep(50);}
            }
            assertThat(ready).withFailMessage(Files.readString(log)).isTrue();
            var uri="redis://127.0.0.1:"+port;
            try(var first=new RouteTable(uri);var second=new RouteTable(uri)) {
            var catalog=new ModelCatalog(Map.of("model-a",new ModelCatalog.Model("model-a",BigDecimal.ONE,BigDecimal.ONE,"http://localhost:9","")));
            var rule=new RouteTable.Rule("chat-qa","staging","model-a","model-a",100,"none",0,"",0);
            first.save(1,List.of(rule),catalog);
            assertThat(second.current().draft()).containsExactly(rule);
            assertThat(second.logical("chat-qa")).isFalse();
            assertThatThrownBy(()->second.save(1,List.of(),catalog)).hasMessageContaining("已被更新");
            second.publish(2,rows->{});
            assertThat(first.logical("chat-qa")).isTrue();
            first.rollback(3,rows->{});
            assertThat(second.logical("chat-qa")).isFalse();
            assertThat(second.current().version()).isEqualTo(3);
            }
        }finally{process.destroy();process.waitFor();Files.deleteIfExists(log);Files.deleteIfExists(dir);}
    }
}
