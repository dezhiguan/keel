package com.keel.server.readmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class InsightReadModelPersistenceTest {
    @Container static PostgreSQLContainer<?> postgres=new PostgreSQLContainer<>("postgres:16-alpine");
    static JdbcTemplate jdbc; static ObjectMapper json; static InsightSnapshots snapshots; static TraceIndexStore index;
    @BeforeAll static void init() {
        var source=new DriverManagerDataSource(postgres.getJdbcUrl(),postgres.getUsername(),postgres.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc=new JdbcTemplate(source);json=new ObjectMapper().findAndRegisterModules();
        snapshots=new InsightSnapshots(jdbc,json);index=new TraceIndexStore(jdbc,json,snapshots,new DataSourceTransactionManager(source));
    }
    @BeforeEach void clear() {jdbc.update("DELETE FROM insight_observation");jdbc.update("DELETE FROM insight_trace");jdbc.update("DELETE FROM insight_trace_bill");jdbc.update("DELETE FROM insight_snapshot");jdbc.update("DELETE FROM agent_run");jdbc.update("DELETE FROM invoke_trace");}
    @Test void leasesAreSharedAndExpiredWritersCannotOverwriteANewerProcess() throws Exception {
        var pool=Executors.newFixedThreadPool(2);
        try {
            var gate=new CountDownLatch(1);
            Callable<String> claim=()->{gate.await();return snapshots.claim("race",Duration.ofMinutes(1));};
            var a=pool.submit(claim);var b=pool.submit(claim);gate.countDown();
            var owners=java.util.stream.Stream.of(a.get(),b.get()).filter(java.util.Objects::nonNull).toList();
            assertThat(owners).hasSize(1);
            var old=owners.getFirst();
            jdbc.update("UPDATE insight_snapshot SET lease_until=now()-interval '1 second' WHERE snapshot_key='race'");
            var fresh=snapshots.claim("race",Duration.ofMinutes(1));assertThat(fresh).isNotNull();
            assertThat(snapshots.finish("race",old,Map.of("value","old"),Duration.ZERO)).isFalse();
            assertThat(snapshots.finish("race",fresh,Map.of("value","new"),Duration.ZERO)).isTrue();
            var restarted=new InsightSnapshots(jdbc,json);
            assertThat(restarted.read("race").payload()).contains("new");
            var retry=restarted.claim("race",Duration.ofMinutes(1));restarted.fail("race",retry,Duration.ofSeconds(60));
            assertThat(restarted.read("race").payload()).contains("new");assertThat(restarted.read("race").failed()).isTrue();
            assertThat(restarted.claim("race",Duration.ofMinutes(1))).isNull();
        } finally {pool.shutdownNow();}
    }
    @Test void crossPageObservationsMergeWithoutDoubleCountingAndTitlesNeverPersistSystemPrompts() throws Exception {
        write(List.of(node("root","tr-a","",0,"prod","a","j1",false)));
        var generation=(com.fasterxml.jackson.databind.node.ObjectNode)node("gen","tr-a","root",1,"prod","a","",false);
        generation.put("type","GENERATION");generation.set("usageDetails",json.readTree("{\"input\":10,\"output\":5}"));
        generation.put("input","SECRET_GENERATION_BODY");generation.put("output","SECRET_REPLY");
        write(List.of(generation));write(List.of(generation));
        var rows=index.list(1,10,"","all",null,null,null,false,null,null);
        assertThat(rows.get("total")).isEqualTo(1L);
        var item=items(rows).getFirst();assertThat(item.get("tokens")).isEqualTo(15);
        assertThat(item.get("question")).isEqualTo("real question");
        assertThat(jdbc.queryForList("SELECT payload::text FROM insight_observation",String.class))
                .allSatisfy(text->assertThat(text).doesNotContain("SYSTEM_SECRET","SECRET_GENERATION_BODY","SECRET_REPLY"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM insight_observation",Integer.class)).isEqualTo(2);
    }
    @Test void allFiltersApplyBeforeCountingAndPaginationAndDelegatedAgentsMatch() throws Exception {
        for(int n=0;n<14;n++) write(List.of(node("r"+n,"t"+n,"",n,n<12?"prod":"dev","a",n%2==0?"j1":"j2",n==0)));
        write(List.of(node("delegate","t0","r0",1,"prod","b","",false)));
        var first=index.list(1,10,"a","prod",null,null,null,false,null,"j1");
        assertThat(first.get("total")).isEqualTo(6L);assertThat(items(first)).hasSize(6);
        assertThat(index.list(1,10,"b","all",null,null,null,true,null,null).get("total")).isEqualTo(1L);
        assertThat(index.list(1,10,"a","prod","blocked",null,null,false,null,null).get("total")).isEqualTo(1L);
        var paged=index.list(2,10,"a","all",null,null,null,false,null,null);
        assertThat(paged.get("total")).isEqualTo(14L);assertThat(items(paged)).hasSize(4);
        assertThat(index.list(1,10,"a","all",null,Instant.parse("2026-10-20T00:00:05Z"),Instant.parse("2026-10-20T00:00:07Z"),false,null,null).get("total")).isEqualTo(2L);
    }
    @Test void suspendedLocalRunsAreDeduplicatedAndResumeImmediatelyWithoutWaitingForLangfuse() throws Exception {
        jdbc.update("INSERT INTO agent(name,display_name,kind,runtime,owner_org,owner_user,status,liveness) VALUES ('a','a','AGENT','code','org','u','REGISTERED','NONE') ON CONFLICT DO NOTHING");
        jdbc.update("INSERT INTO agent_run(run_id,agent_name,env,trace_id,status,suspend_reason,actor_user) VALUES ('qa-run','a','prod','tr-a','SUSPENDED','input_required','54')");
        assertThat(index.list(1,10,"a","prod","pending",null,null,false,null,null).get("total")).isEqualTo(1L);
        write(List.of(node("root","tr-a","",0,"prod","a","j1",false)));
        assertThat(index.list(1,10,"a","prod","pending",null,null,false,null,null).get("total")).isEqualTo(1L);
        jdbc.update("UPDATE agent_run SET status='DONE' WHERE run_id='qa-run'");
        assertThat(index.list(1,10,"a","prod","pending",null,null,false,null,null).get("total")).isEqualTo(0L);
        assertThat(index.list(1,10,"a","prod","ok",null,null,false,null,null).get("total")).isEqualTo(1L);
    }
    @Test void pendingBillsOverrideAProvisionalZeroAndSettlementIsSharedWithTheLedgerAtomically() throws Exception {
        write(List.of(node("guard","tr-bill","",0,"prod","a","",true)));
        var ledger=snapshots.decode("""
                {"bills":[{"traceId":"tr-bill","status":"PENDING","costCny":null}],"keys":[],"models":[],"rules":[]}
                """,com.keel.server.insight.CostService.Ledger.class);
        var owner=snapshots.claim("overview-ledger-v1",Duration.ofMinutes(1));
        assertThat(index.replaceBills(owner,ledger)).isTrue();
        var item=items(index.list(1,10,"","all",null,null,null,false,null,null)).getFirst();
        assertThat(item.get("costCny")).isNull();assertThat(item.get("pendingBillCount")).isEqualTo(1);
        assertThat(snapshots.read("overview-ledger-v1").payload()).isNotNull();
        jdbc.update("UPDATE insight_snapshot SET retry_at=now() WHERE snapshot_key='overview-ledger-v1'");
        owner=snapshots.claim("overview-ledger-v1",Duration.ofMinutes(1));
        ledger=snapshots.decode("""
                {"bills":[{"traceId":"tr-bill","status":"SETTLED","costCny":0.25}],"keys":[],"models":[],"rules":[]}
                """,com.keel.server.insight.CostService.Ledger.class);
        assertThat(index.replaceBills(owner,ledger)).isTrue();
        item=items(index.list(1,10,"","all",null,null,null,false,null,null)).getFirst();
        assertThat(item.get("costCny")).isEqualTo(.25);assertThat(item.get("pendingBillCount")).isNull();
    }

    @Test void failedPageDoesNotMoveCheckpointAndReplacedLeaseCannotWriteIndex() throws Exception {
        var owner=snapshots.claim(TraceIndexStore.KEY,Duration.ofMinutes(1));
        snapshots.finish(TraceIndexStore.KEY,owner,Map.of("cursor","first"),Duration.ZERO);
        owner=snapshots.claim(TraceIndexStore.KEY,Duration.ofMinutes(1));snapshots.fail(TraceIndexStore.KEY,owner,Duration.ofSeconds(10));
        assertThat(snapshots.read(TraceIndexStore.KEY).payload()).contains("first");
        index.accept(owner,List.of(node("root","tr-a","",0,"prod","a","",false)),Map.of("cursor","bad"),Duration.ZERO);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM insight_trace",Integer.class)).isZero();
        assertThat(snapshots.read(TraceIndexStore.KEY).payload()).contains("first");
    }
    void write(List<JsonNode> rows) {
        jdbc.update("UPDATE insight_snapshot SET retry_at=now() WHERE snapshot_key=?",TraceIndexStore.KEY);
        var owner=snapshots.claim(TraceIndexStore.KEY,Duration.ofMinutes(1));assertThat(owner).isNotNull();
        index.accept(owner,rows,Map.of("cursor","next"),Duration.ZERO);
    }
    JsonNode node(String id,String trace,String parent,int offset,String env,String agent,String job,boolean blocked) throws Exception {
        var row=json.createObjectNode();row.put("id",id);row.put("traceId",trace);row.put("parentObservationId",parent);
        row.put("name",blocked?"guard.input":"agent.run");row.put("type",blocked?"GUARDRAIL":"AGENT");
        row.put("startTime",Instant.parse("2026-10-20T00:00:00Z").plusSeconds(offset).toString());row.put("endTime",Instant.parse("2026-10-20T00:00:00Z").plusSeconds(offset+1).toString());
        row.set("metadata",json.valueToTree(Map.of("keel.agent",agent,"keel.env",env,"keel.devflow.job_id",job,"keel.status",blocked?"failed":"ok","keel.blocked",String.valueOf(blocked))));
        row.set("input",json.readTree("[{\"role\":\"system\",\"content\":\"SYSTEM_SECRET\"},{\"role\":\"user\",\"content\":\"real question\"}]"));
        return row;
    }
    @SuppressWarnings("unchecked") List<Map<String,Object>> items(Map<String,Object> result) {return (List<Map<String,Object>>)result.get("items");}
}
