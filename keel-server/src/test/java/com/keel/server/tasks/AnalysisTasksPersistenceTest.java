package com.keel.server.tasks;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.keel.server.auth.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

/** Dedicated real PG only. Identities/model artifacts here are fixtures, never real business evidence. */
@EnabledIfEnvironmentVariable(named="KEEL_TASK_PG_URL",matches=".+")
class AnalysisTasksPersistenceTest {
    private JdbcTemplate db;private TaskContext contexts;private AnalysisTasks tasks;
    private final ObjectMapper json=new ObjectMapper();
    private final ConsolePrincipal user=new ConsolePrincipal("USER","fixture-a","fixture-user","fixture","平台组","ADMIN",List.of(),false);
    private final String material="库存没有备份。审批依赖一个人。上线没有回退预案。";
    private final String text="请根据以下材料生成分析报告，总结主要内容，列出三个风险及依据和建议。材料："+material;
    @BeforeEach void setup()throws Exception{
        db=new JdbcTemplate(new DriverManagerDataSource(System.getenv("KEEL_TASK_PG_URL"),System.getenv().getOrDefault("KEEL_TASK_PG_USER","amy"),System.getenv().getOrDefault("KEEL_TASK_PG_PASSWORD","")));
        assertThat(db.queryForObject("SELECT current_database()",String.class)).isEqualTo("keel_assistant_platform_task_test");
        db.execute("DROP TABLE IF EXISTS assistant_analysis_attempt,assistant_analysis_task,assistant_analysis_scope,tool_version,tool,agent_tool_grant,agent");
        db.execute("CREATE TABLE agent(name text PRIMARY KEY,owner_org text,status text)");
        db.execute("INSERT INTO agent VALUES ('keel-assistant','平台组','ONLINE')");
        db.execute("CREATE TABLE agent_tool_grant(agent_name text,tool_name text,version_range text,granted_by text,status text,UNIQUE(agent_name,tool_name,version_range))");
        db.execute("CREATE TABLE tool(name text PRIMARY KEY,scope text,owner_org text,provider text,status text CHECK(status IN ('ONLINE','REGISTERED','RETIRED')))");
        db.execute("CREATE TABLE tool_version(tool_name text,version text,schema_json jsonb,description text,access text,risk text,breaking boolean,UNIQUE(tool_name,version))");
        db.execute(Files.readString(Path.of("src/main/resources/db/migration/V33__assistant_analysis_tasks.sql")));
        db.update("INSERT INTO assistant_analysis_scope VALUES ('keel-assistant','staging','平台组',true,'fixture',now())");
        contexts=new TaskContext(new ConsoleSigningKey(),new ConsoleUsers("fixture-user"));tasks=new AnalysisTasks(db,json,contexts);
    }
    private ObjectNode args(String op,String raw,String request,String namespace){
        return json.createObjectNode().put("operation",op).put("_text",raw)
            .put("_authorization",contexts.issueUser(user,"keel-assistant","staging","s",raw,request,false,namespace))
            .put("_session",TaskContext.hash("fixture-a:s"));
    }
    private Map<String,Object> create(){return tasks.command(args("create",text,"r1","user").put("material",material),"trace-fixture");}
    private ObjectNode get(String id){return args("get","查询任务 "+id,"get-r1","user").put("task_id",id);}
    private String report()throws Exception{
        var root=json.createObjectNode().put("summary","材料暴露备份、审批和回退方面的不足。");var risks=root.putArray("risks");
        for(String quote:List.of("库存没有备份","审批依赖一个人","上线没有回退预案")){
            var risk=risks.addObject().put("risk",quote+"的风险").put("recommendation","建立复核与补救流程");
            risk.putObject("evidence").put("source_id","material").put("quote",quote);
        }return json.writeValueAsString(root);
    }
    @Test void duplicateRequestDeduplicatesAndDifferentContentConflicts(){
        var a=create();var b=create();assertThat(a.get("task_id")).isEqualTo(b.get("task_id"));
        assertThat(db.queryForObject("SELECT count(*) FROM assistant_analysis_task",Integer.class)).isEqualTo(1);
        assertThatThrownBy(()->tasks.command(args("create",text+"补充目标","r1","user").put("material",material),"t")).isInstanceOf(RuntimeException.class);
    }
    @Test void rejectsDiscussionFakeIdentityMismatchedSessionAndFabricatedMaterial(){
        assertThatThrownBy(()->tasks.command(args("create","我们讨论如何生成分析报告，材料："+material,"r1","user").put("material",material),"t")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->tasks.command(args("create",text,"r1","user").put("user_id","other").put("material",material),"t")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->tasks.command(args("create",text,"r1","user").put("_session","wrong").put("material",material),"t")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->tasks.command(args("create",text,"r1","user").put("material","虚构资料"),"t")).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->tasks.command(args("create","请读取链接生成报告：https://example.com","r1","user").put("material","https://example.com"),"t")).isInstanceOf(RuntimeException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM assistant_analysis_task",Integer.class)).isZero();
    }
    @Test void differentUserAndAcceptanceNamespaceCannotReadNormalTasks(){
        var id=(String)create().get("task_id");
        assertThatThrownBy(()->tasks.command(args("get","查询任务","r2","acceptance:qa").put("task_id",id),"t")).isInstanceOf(RuntimeException.class);
        var other=new ConsolePrincipal("USER","fixture-b","fixture-user","fixture","平台组","ADMIN",List.of(),false);
        var request=get(id).put("_authorization",contexts.issueUser(other,"keel-assistant","staging","s","查询任务","r2",false,"user")).put("_session",TaskContext.hash("fixture-b:s"));
        assertThatThrownBy(()->tasks.command(request,"t")).isInstanceOf(RuntimeException.class);
        tasks.command(args("create",text,"r1","acceptance:qa").put("material",material),"t");
        assertThat((List<?>)tasks.command(args("list","查询任务","list1","user"),"t").get("items")).hasSize(1);
    }
    @Test void twoWorkersCompeteAndStaleWorkerCannotWriteAfterLeaseExpiry()throws Exception{
        create();var barrier=new CyclicBarrier(2);var pool=Executors.newFixedThreadPool(2);
        try{
            var futures=List.of(pool.submit(()->{barrier.await();return tasks.claim("a");}),pool.submit(()->{barrier.await();return tasks.claim("b");}));
            var claims=new ArrayList<Map<String,Object>>();for(var f:futures){var row=f.get();if(row!=null)claims.add(row);}
            assertThat(claims).hasSize(1);var first=claims.getFirst();
            db.update("UPDATE assistant_analysis_task SET lease_until=now()-interval '1 second'");
            var replacement=tasks.claim("c");assertThat(replacement).isNotNull();
            assertThat(((Number)replacement.get("fence")).longValue()).isGreaterThan(((Number)first.get("fence")).longValue());
            assertThatThrownBy(()->tasks.start(first)).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(()->tasks.finish(first,report(),1,null)).isInstanceOf(RuntimeException.class);
        }finally{pool.shutdownNow();}
    }
    @Test void completeReportPersistsAndRestartedServiceDoesNotExecuteAgain()throws Exception{
        var id=(String)create().get("task_id");var task=tasks.start(tasks.claim("worker"));
        tasks.finish(task,report(),1,null);
        var restarted=new AnalysisTasks(db,json,contexts);var result=restarted.command(get(id),"t");
        assertThat(result.get("state")).isEqualTo("succeeded");assertThat(result.get("report")).isEqualTo(report());
        assertThat(result.get("cost_cny")).isNull();assertThat(restarted.claim("b")).isNull();
    }
    @Test void postIntentLossAndTimeoutAreUnknownAndNeverAutomaticallyReplayed(){
        create();var task=tasks.start(tasks.claim("worker"));
        db.update("UPDATE assistant_analysis_task SET lease_until=now()-interval '1 second'");
        assertThat(tasks.claim("replacement")).isNull();
        assertThat(db.queryForObject("SELECT state FROM assistant_analysis_task",String.class)).isEqualTo("unknown");
        assertThatThrownBy(()->tasks.finish(task,null,null,"timeout")).isInstanceOf(RuntimeException.class);
    }
    @Test void queuedCancelAndRunningCancellationHaveDifferentSemantics()throws Exception{
        var id=(String)create().get("task_id");
        assertThat(tasks.command(args("cancel","取消任务","c1","user").put("task_id",id),"t").get("state")).isEqualTo("cancelled");
        assertThat(tasks.claim("worker")).isNull();
        var other=tasks.command(args("create",text,"r2","user").put("material",material),"t");
        var task=tasks.start(tasks.claim("worker"));
        assertThat(tasks.command(args("cancel","取消任务 "+other.get("task_id"),"c2","user").put("task_id",(String)other.get("task_id")),"t").get("state")).isEqualTo("cancel_requested");
        tasks.finish(task,report(),1,null);
        assertThat(tasks.command(get((String)other.get("task_id")),"t").get("state")).isEqualTo("cancelled");
        assertThat(db.queryForObject("SELECT report FROM assistant_analysis_attempt WHERE task_id=?",String.class,other.get("task_id"))).isEqualTo(report());
    }
    @Test void missingMalformedUnsupportedAndAmbiguousArtifactsDoNotSucceed()throws Exception{
        assertThat(tasks.validate(null,material)).isNotEmpty();assertThat(tasks.validate("done",material)).isNotEmpty();
        assertThat(tasks.validate(report().replace("库存没有备份","完全虚构的事实"),material)).isNotEmpty();
        assertThat(tasks.validate("{\"summary\":\"a\",\"summary\":\"b\",\"risks\":[]}",material)).isNotEmpty();
        assertThat(tasks.validate(report(),material)).isEmpty();
        var id=(String)create().get("task_id");var task=tasks.start(tasks.claim("worker"));tasks.finish(task,"bad artifact",1,null);
        assertThat(tasks.command(get(id),"t").get("state")).isEqualTo("failed");
        assertThat(db.queryForObject("SELECT report FROM assistant_analysis_attempt",String.class)).isEqualTo("bad artifact");
    }
    @Test void expirationAndRevocationWaitForAuthorizationWithoutDispatch(){
        create();db.update("UPDATE assistant_analysis_task SET authorized_until=now()-interval '1 second'");
        assertThat(tasks.claim("worker")).isNull();
        assertThat(db.queryForObject("SELECT state FROM assistant_analysis_task",String.class)).isEqualTo("waiting_authorization");
        assertThat(db.queryForObject("SELECT attempt FROM assistant_analysis_task",Integer.class)).isZero();
    }
    @Test void executionTicketBoundToLeaseCanBeConsumedOnlyOnce()throws Exception{
        create();var task=tasks.start(tasks.claim("worker"));var ticket=contexts.issueExecution(task);
        assertThat(tasks.execution(ticket,"a".repeat(32),"b".repeat(32)).get("material")).isEqualTo(material);
        assertThatThrownBy(()->tasks.execution(ticket,"a".repeat(32),"c".repeat(32))).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(()->contexts.verify(ticket,"user")).isInstanceOf(RuntimeException.class);
    }
    @Test void retryRequiresExplicitCostConsentAndPreservesPreviousAttemptArtifacts()throws Exception{
        var id=(String)create().get("task_id");var first=tasks.start(tasks.claim("a"));tasks.finish(first,"invalid first report",1,null);
        assertThatThrownBy(()->tasks.command(args("retry","重试 "+id+" 会有费用吗","retry1","user").put("task_id",id).put("acknowledge_duplicate_cost",true),"t")).isInstanceOf(RuntimeException.class);
        var consent=args("retry","我接受可能重复计算和费用，请重试 "+id,"retry2","user").put("task_id",id).put("acknowledge_duplicate_cost",true);
        assertThat(tasks.command(consent,"t").get("state")).isEqualTo("queued");
        tasks.finish(tasks.start(tasks.claim("b")),report(),1,null);
        assertThat(db.queryForList("SELECT report FROM assistant_analysis_attempt ORDER BY attempt",String.class)).containsExactly("invalid first report",report());
        var queried=tasks.command(get(id),"t");
        assertThat(queried.get("attempt")).isEqualTo(2);
        var history=(List<?>)queried.get("attempts");assertThat(history).hasSize(2);
        assertThat(((Map<?,?>)history.getFirst()).get("report")).isEqualTo("invalid first report");
        assertThat(((Map<?,?>)history.getFirst()).get("state")).isEqualTo("failed");
        assertThat(((Map<?,?>)history.getLast()).get("report")).isEqualTo(report());
        assertThat(((Map<?,?>)history.getLast()).get("acceptance")).isInstanceOf(JsonNode.class);
    }
    @Test void cancellingQueuedRetryDoesNotClaimEarlierUnknownCallStopped(){
        var id=(String)create().get("task_id");var task=tasks.start(tasks.claim("a"));tasks.finish(task,null,null,"ambiguous timeout");
        tasks.command(args("retry","确认可能重复计算和费用，重试 "+id,"r2","user").put("task_id",id).put("acknowledge_duplicate_cost",true),"t");
        var cancelled=tasks.command(args("cancel","取消任务 "+id,"c2","user").put("task_id",id),"t");
        assertThat(cancelled.get("state")).isEqualTo("cancelled");
        assertThat((String)cancelled.get("cancellation_note")).contains("先前调用不保证已停止","费用不能撤回");
        var history=(List<?>)tasks.command(get(id),"t").get("attempts");assertThat(history).hasSize(1);
        assertThat(((Map<?,?>)history.getFirst()).get("state")).isEqualTo("unknown");
        assertThat(((Map<?,?>)history.getFirst()).get("error")).isEqualTo("ambiguous timeout");
        assertThat(tasks.claim("b")).isNull();
    }
    @Test void retentionOnlyRemovesOldTerminalTasksAndDoesNotDeleteUnknownCalls(){
        var id=(String)create().get("task_id");var task=tasks.start(tasks.claim("a"));tasks.finish(task,null,null,"ambiguous timeout");
        db.update("UPDATE assistant_analysis_task SET updated_at=now()-interval '31 days'");
        tasks.expireTerminalArtifacts();assertThat(tasks.command(get(id),"t").get("state")).isEqualTo("unknown");
        db.update("UPDATE assistant_analysis_task SET state='failed'");tasks.expireTerminalArtifacts();
        assertThat(db.queryForObject("SELECT count(*) FROM assistant_analysis_task",Integer.class)).isZero();
        assertThat(db.queryForObject("SELECT count(*) FROM assistant_analysis_attempt",Integer.class)).isZero();
    }
    @Test void ambiguousReferenceDoesNotSelectAnArbitraryTask(){
        var first=create();tasks.command(args("create",text,"r2","user").put("material",material),"t");
        assertThatThrownBy(()->tasks.command(args("cancel","取消刚才那个任务","c1","user").put("task_id",(String)first.get("task_id")),"t")).isInstanceOf(RuntimeException.class);
        assertThat(db.queryForObject("SELECT count(*) FROM assistant_analysis_task WHERE state='queued'",Integer.class)).isEqualTo(2);
    }
    @Test void explicitUserScopeGrantsOnlyTwoExactToolsAndCanBeRevoked(){
        var request=new org.springframework.mock.web.MockHttpServletRequest();ConsolePrincipal.set(request,user);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
        var audits=new java.util.concurrent.atomic.AtomicInteger();
        try{
            var controller=new AnalysisTaskController(tasks,db,audit(audits,false),json);
            controller.scope("keel-assistant",json.createObjectNode().put("environment","staging").put("enabled",true));
            controller.scope("keel-assistant",json.createObjectNode().put("environment","staging").put("enabled",true));
            assertThat(db.queryForObject("SELECT count(*) FROM agent_tool_grant WHERE status='ACTIVE'",Integer.class)).isEqualTo(2);
            controller.scope("keel-assistant",json.createObjectNode().put("environment","staging").put("enabled",false));
            assertThat(db.queryForObject("SELECT count(*) FROM agent_tool_grant WHERE status='ACTIVE'",Integer.class)).isZero();
            assertThat(audits.get()).isEqualTo(3);
            ConsolePrincipal.set(request,ConsolePrincipal.service("keel-assistant"));
            assertThatThrownBy(()->controller.scope("keel-assistant",json.createObjectNode().put("environment","staging").put("enabled",true))).isInstanceOf(RuntimeException.class);
        }finally{org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
    }
    @Test void auditFailureRejectsScopeWriteBeforeAnyPermissionMutation(){
        var request=new org.springframework.mock.web.MockHttpServletRequest();ConsolePrincipal.set(request,user);
        org.springframework.web.context.request.RequestContextHolder.setRequestAttributes(new org.springframework.web.context.request.ServletRequestAttributes(request));
        try{
            var controller=new AnalysisTaskController(tasks,db,audit(new java.util.concurrent.atomic.AtomicInteger(),true),json);
            assertThatThrownBy(()->controller.scope("keel-assistant",json.createObjectNode().put("environment","staging").put("enabled",false))).isInstanceOf(RuntimeException.class);
            assertThat(db.queryForObject("SELECT enabled FROM assistant_analysis_scope",Boolean.class)).isTrue();
            assertThat(db.queryForObject("SELECT count(*) FROM agent_tool_grant",Integer.class)).isZero();
        }finally{org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();}
    }
    @Test void assistantValidationVetoRetainsOriginalReportButCannotSucceed()throws Exception{
        var id=(String)create().get("task_id");var task=tasks.start(tasks.claim("a"));
        tasks.finish(task,report(),1,null,List.of("fixture independent validation failed"));
        var result=tasks.command(get(id),"t");assertThat(result.get("state")).isEqualTo("failed");
        assertThat(result.get("report")).isEqualTo(report());
    }
    private com.keel.server.integration.audit.AuditStore audit(java.util.concurrent.atomic.AtomicInteger count,boolean fail){
        return new com.keel.server.integration.audit.AuditStore(){
            public void append(String agent,String env,String action,String risk,String decision,String resource,String trace){if(fail)throw new IllegalStateException("fixture audit failure");count.incrementAndGet();}
            public Map<String,Object> page(String agent,String risk,String env,int page,int size){return Map.of();}
            public Map<String,Object> verify(String agent){return Map.of();}
        };
    }
}
