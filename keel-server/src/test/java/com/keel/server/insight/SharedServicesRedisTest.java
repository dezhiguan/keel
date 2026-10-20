package com.keel.server.insight;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.server.integration.redis.ServiceSnapshotRedis;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class SharedServicesRedisTest {
    @Container static GenericContainer<?> container=new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    static RedisClient controlClient;static StatefulRedisConnection<String,String> control;
    ServiceSnapshotRedis first,second;
    static String uri(){return "redis://"+container.getHost()+":"+container.getMappedPort(6379)+"/0";}
    @BeforeAll static void connect(){controlClient=RedisClient.create(uri());control=controlClient.connect();}
    @BeforeEach void setup(){control.sync().flushdb();first=new ServiceSnapshotRedis(uri());second=new ServiceSnapshotRedis(uri());}
    @AfterEach void close(){first.close();second.close();}
    @AfterAll static void shutdown(){control.close();controlClient.shutdown();}

    @Test void twoInstancesJoinOneColdReadAndARecreatedProcessReusesTheSnapshot() throws Exception {
        var contended=new CountDownLatch(1);
        second.close();
        second=new ServiceSnapshotRedis(uri()) {
            @Override public String claim(String scope,Duration lease){var owner=super.claim(scope,lease);if(owner==null)contended.countDown();return owner;}
        };
        var clock=new SharedServicesCacheTest.MutableClock();var one=new SharedServicesCache(first,new ObjectMapper(),clock);
        var two=new SharedServicesCache(second,new ObjectMapper(),clock);var started=new CountDownLatch(1);var release=new CountDownLatch(1);var reads=new AtomicInteger();
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=pool.submit(()->one.get("prod",()->{reads.incrementAndGet();started.countDown();try {release.await(4,TimeUnit.SECONDS);}catch(InterruptedException e){throw new IllegalStateException(e);}return Map.of("env","prod","version",1);}));
            assertThat(started.await(2,TimeUnit.SECONDS)).isTrue();
            var b=pool.submit(()->two.get("prod",()->{throw new AssertionError("another instance must not repeat probes");}));
            assertThat(contended.await(2,TimeUnit.SECONDS)).isTrue();release.countDown();
            assertThat(a.get(3,TimeUnit.SECONDS)).isEqualTo(b.get(3,TimeUnit.SECONDS));assertThat(reads).hasValue(1);
            var restarted=new SharedServicesCache(second,new ObjectMapper(),clock);
            assertThat(restarted.get("prod",()->{throw new AssertionError("restart must reuse Redis");}).get("version")).isEqualTo(1);
            assertThat(restarted.get("dev",()->Map.of("env","dev")).get("env")).isEqualTo("dev");
            assertThat(control.sync().pttl("keel:server:services:v1:{prod}:snapshot")).isBetween(290000L,300000L);
        } finally {release.countDown();}
    }
    @Test void lostOwnerCannotPublishOrDeleteTheNextOwnersLockAndRenewalExtendsTheLease() throws Exception {
        var old=first.claim("all",Duration.ofMillis(70));assertThat(old).isNotNull();Thread.sleep(120);
        var current=second.claim("all",Duration.ofSeconds(2));assertThat(current).isNotNull();
        assertThat(first.publish("all",old,"old",Duration.ofMinutes(5))).isFalse();
        first.release("all",old);assertThat(first.claim("all",Duration.ofSeconds(2))).isNull();
        assertThat(second.renew("all",current,Duration.ofSeconds(90))).isTrue();
        assertThat(control.sync().pttl("keel:server:services:v1:{all}:lock")).isBetween(85000L,90000L);
        assertThat(second.publish("all",current,"new",Duration.ofMinutes(5))).isTrue();
        assertThat(first.read("all")).isEqualTo("new");
        assertThat(first.claim("all",Duration.ofSeconds(2))).isNotNull();
    }
    @Test void staleReadsRefreshThroughRedisAndKeepTheOldCollectionTimeOnFailure() throws Exception {
        var clock=new SharedServicesCacheTest.MutableClock();var one=new SharedServicesCache(first,new ObjectMapper(),clock);
        var original=one.get("all",()->Map.of("version",1));clock.advance(Duration.ofSeconds(31));
        var two=new SharedServicesCache(second,new ObjectMapper(),clock);var done=new CountDownLatch(1);
        var old=two.get("all",()->{done.countDown();throw new IllegalStateException("probe unavailable");});
        assertThat(SharedServicesCacheTest.status(old).get("state")).isEqualTo("stale");assertThat(done.await(2,TimeUnit.SECONDS)).isTrue();
        assertThat(SharedServicesCacheTest.status(old).get("updatedAt")).isEqualTo(SharedServicesCacheTest.status(original).get("updatedAt"));
        assertThat(new ObjectMapper().readTree(first.read("all")).path("value").path("version").asInt()).isEqualTo(1);
    }
}
