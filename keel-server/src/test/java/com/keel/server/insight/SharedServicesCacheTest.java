package com.keel.server.insight;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.integration.redis.ServiceSnapshotRedis;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class SharedServicesCacheTest {
    @Test void staleSnapshotReturnsImmediatelyAndOnlyOneRefreshRuns() throws Exception {
        var clock=new MutableClock();var cache=new SharedServicesCache(null,new ObjectMapper(),clock);
        var reads=new AtomicInteger();var started=new CountDownLatch(1);var finish=new CountDownLatch(1);
        var initial=cache.get("all",()->{reads.incrementAndGet();return Map.of("version",1);});
        clock.advance(Duration.ofSeconds(31));
        try {
            var visible=cache.get("all",()->{reads.incrementAndGet();started.countDown();try {finish.await(3,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IllegalStateException(e);}return Map.of("version",2);});
            assertThat(visible.get("version")).isEqualTo(1);
            assertThat(status(visible).get("state")).isEqualTo("stale");
            assertThat(status(visible).get("updatedAt")).isEqualTo(status(initial).get("updatedAt"));
            assertThat(started.await(1,TimeUnit.SECONDS)).isTrue();
            for(int n=0;n<8;n++) assertThat(cache.get("all",()->{throw new AssertionError("duplicate refresh");}).get("version")).isEqualTo(1);
            assertThat(reads).hasValue(2);
        } finally {finish.countDown();}
    }
    @Test void failedRefreshNeverMovesTimestampAndExpiredHealthIsNotServed() throws Exception {
        var clock=new MutableClock();var cache=new SharedServicesCache(null,new ObjectMapper(),clock);
        var initial=cache.get("all",()->Map.of("status","ONLINE"));
        clock.advance(Duration.ofSeconds(31));
        var failed=new CountDownLatch(1);
        var stale=cache.get("all",()->{failed.countDown();throw new IllegalStateException("probe failed");});
        assertThat(failed.await(1,TimeUnit.SECONDS)).isTrue();
        assertThat(status(stale).get("updatedAt")).isEqualTo(status(initial).get("updatedAt"));
        assertThat(status(stale).get("state")).isEqualTo("stale");
        // Allow the failed background owner to release the in-process flight before expiring the snapshot.
        Thread.sleep(50);
        clock.advance(Duration.ofMinutes(5));
        assertThatThrownBy(()->cache.get("all",()->{throw new IllegalStateException("still down");})).isInstanceOf(IllegalStateException.class).hasMessage("still down");
    }
    @Test void localFallbackSurvivesRedisFailureAndKeepsEnvironmentsSeparate() {
        var broken=new ServiceSnapshotRedis("") {
            @Override public boolean configured(){return true;}
            @Override public String read(String scope){throw new IllegalStateException("offline");}
            @Override public String claim(String scope,Duration lease){throw new IllegalStateException("offline");}
        };
        var cache=new SharedServicesCache(broken,new ObjectMapper(),new MutableClock());var reads=new AtomicInteger();
        assertThat(cache.get("prod",()->{reads.incrementAndGet();return Map.of("env","prod");}).get("env")).isEqualTo("prod");
        assertThat(cache.get("prod",()->{throw new AssertionError("must reuse local data");}).get("env")).isEqualTo("prod");
        assertThat(cache.get("dev",()->{reads.incrementAndGet();return Map.of("env","dev");}).get("env")).isEqualTo("dev");
        assertThat(reads).hasValue(2);
    }
    @SuppressWarnings("unchecked") static Map<String,Object> status(Map<String,Object> value){return (Map<String,Object>)value.get("dataStatus");}
    static class MutableClock extends Clock {
        private volatile Instant now=Instant.parse("2026-10-20T10:00:00Z");
        void advance(Duration amount){now=now.plus(amount);}
        @Override public ZoneId getZone(){return ZoneOffset.UTC;}
        @Override public Clock withZone(ZoneId zone){return this;}
        @Override public Instant instant(){return now;}
    }
}
