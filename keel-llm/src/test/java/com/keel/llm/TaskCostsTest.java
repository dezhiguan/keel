package com.keel.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.*;
import org.springframework.jdbc.core.JdbcTemplate;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import static org.assertj.core.api.Assertions.*;

@Testcontainers(disabledWithoutDocker=true)
class TaskCostsTest {
    @Container static final PostgreSQLContainer<?> PG=new PostgreSQLContainer<>("postgres:16-alpine");
    JdbcSpendLedger bills;TaskCosts costs;JdbcTemplate db;ObjectMapper json=new ObjectMapper();
    @BeforeEach void setup(){
        bills=new JdbcSpendLedger(PG.getJdbcUrl(),PG.getUsername(),PG.getPassword());costs=new TaskCosts(bills.dataSource());db=new JdbcTemplate(bills.dataSource());
        db.execute("TRUNCATE llm_cost_task_share,llm_cost_request,llm_cost_task,llm_cost_required_alias,llm_spend,llm_daily_recovery");
    }
    Map<String,Object> root(String owner,String mode,String budget){
        return costs.open(json.valueToTree(Map.of("alias","test-agent-staging","owner",owner,"group","user","budgetCny",new BigDecimal(budget),
                "policy",Map.of("mode",mode,"maxCostCny",1,"maxCalls",8,"version","test@1"))));
    }
    void bill(String request,String status,String cost,boolean timeout){
        bills.append(new Gateway.Spend("test-agent-staging","qwen-plus",20,10,cost==null?null:new BigDecimal(cost),request,Instant.now(),2,timeout,status,BigDecimal.ZERO,UUID.randomUUID().toString(),"trace","v1","primary",0));
        costs.reconcileRequest(request);
    }
    @Test void concurrentRequestsShareOneAtomicBudget() throws Exception {
        var root=root("u","enforce","0.02");var start=new CountDownLatch(1);var results=new ArrayList<Future<Boolean>>();
        try(var pool=Executors.newFixedThreadPool(2)) {
            for(int i=0;i<2;i++){String r="request-"+i;results.add(pool.submit(()->{start.await();try{costs.admit("test-agent-staging",root.get("ticket").toString(),r,"interactive",new BigDecimal("0.015"),1);return true;}catch(Gateway.GatewayException e){assertThat(e.code()).isEqualTo(ErrorCode.COST_BUDGET_EXCEEDED);return false;}}));}
            start.countDown();int accepted=0;for(var r:results)if(r.get(10,TimeUnit.SECONDS))accepted++;
            assertThat(accepted).isEqualTo(1);
        }
        assertThat(costs.inspect(root.get("taskId").toString()).get("reservedCny")).isEqualTo(new BigDecimal("0.015000000"));
    }
    @Test void definitiveSettlementReplacesReserveAndUnknownUsageDoesNotDisappear(){
        var root=root("u","enforce","0.03");String id=root.get("taskId").toString(),ticket=root.get("ticket").toString();
        costs.admit("test-agent-staging",ticket,"settled","interactive",new BigDecimal("0.02"),1);bill("settled","SETTLED","0.005",false);
        var first=costs.inspect(id);assertThat(new BigDecimal(first.get("settledCny").toString())).isEqualByComparingTo("0.005");assertThat(new BigDecimal(first.get("reservedCny").toString())).isZero();
        costs.admit("test-agent-staging",ticket,"unknown","memory",new BigDecimal("0.02"),1);bill("unknown","PENDING",null,false);
        assertThat(costs.inspect(id).get("costState")).isEqualTo("pending");
        assertThatThrownBy(()->costs.admit("test-agent-staging",ticket,"more","interactive",new BigDecimal("0.01"),1)).isInstanceOf(Gateway.GatewayException.class);
        db.update("UPDATE llm_spend SET status='SETTLED',cost_cny=0.004 WHERE request_id='unknown'");
        var late=costs.inspect(id);assertThat(new BigDecimal(late.get("settledCny").toString())).isEqualByComparingTo("0.009");assertThat(late.get("costState")).isEqualTo("settled");
    }
    @Test void sharedMaintenanceConservesActualMoneyAndChecksBothParents(){
        var a=root("u","enforce","0.04");var b=root("u","enforce","0.04");
        var shared=costs.open(json.valueToTree(Map.of("alias","test-agent-staging","owner","u","group","memory","budgetCny",1,
                "policy",Map.of("mode","enforce","maxCostCny",1,"maxCalls",8,"version","test@1"),"shares",List.of(Map.of("taskId",a.get("taskId"),"weight",.5),Map.of("taskId",b.get("taskId"),"weight",.5)))));
        costs.admit("test-agent-staging",shared.get("ticket").toString(),"shared","memory",new BigDecimal("0.02"),1);bill("shared","SETTLED","0.01",false);
        BigDecimal one=new BigDecimal(costs.inspect(a.get("taskId").toString()).get("settledCny").toString());
        BigDecimal two=new BigDecimal(costs.inspect(b.get("taskId").toString()).get("settledCny").toString());assertThat(one.add(two)).isEqualByComparingTo("0.01");
        assertThatThrownBy(()->costs.admit("test-agent-staging",shared.get("ticket").toString(),"oversized","memory",new BigDecimal("0.08"),1)).isInstanceOf(Gateway.GatewayException.class);
    }
    @Test void observeRecordsViolationsAndPublicReadNeverReturnsTicket(){
        var root=root("u","observe","0.01");costs.admit("test-agent-staging",root.get("ticket").toString(),"observation","analysis",new BigDecimal("0.03"),1);
        var view=costs.inspect(root.get("taskId").toString());assertThat(((Number)view.get("violations")).intValue()).isEqualTo(1);assertThat(view.toString()).doesNotContain(root.get("ticket").toString()).doesNotContain("ticket_hash");
    }
    @Test void aliasIsolationAndRequiredContextCannotBeBypassed(){
        var root=root("u","enforce","0.1");
        assertThatThrownBy(()->costs.admit("other-agent-staging",root.get("ticket").toString(),"x","interactive",BigDecimal.ONE,1)).isInstanceOf(Gateway.GatewayException.class);
        assertThatThrownBy(()->costs.checkContext("test-agent-staging","")).isInstanceOf(Gateway.GatewayException.class);
        costs.required("test-agent-staging",false);costs.checkContext("test-agent-staging","");
    }
    @Test void tighterLiveConstraintsApplyWithoutMutatingTheTaskSnapshot(){
        var root=root("u","observe","0.1");
        costs.constraints("test-agent-staging",true,new BigDecimal("0.01"),2);
        assertThatThrownBy(()->costs.admit("test-agent-staging",root.get("ticket").toString(),"blocked","interactive",new BigDecimal("0.02"),1)).isInstanceOf(Gateway.GatewayException.class);
        assertThat(costs.inspect(root.get("taskId").toString()).get("mode")).isEqualTo("observe");
        assertThat(db.queryForObject("SELECT count(*) FROM llm_cost_request",Integer.class)).isZero();
        costs.constraints("test-agent-staging",false,new BigDecimal("0.01"),2);
        costs.admit("test-agent-staging",root.get("ticket").toString(),"observed","interactive",new BigDecimal("0.02"),1);
    }
    @Test void aSettledAttemptCannotReleaseTheRemainingBoundBeforeRequestCompletion(){
        var root=root("u","enforce","0.03");String id=root.get("taskId").toString(),ticket=root.get("ticket").toString();
        costs.admit("test-agent-staging",ticket,"in-flight","interactive",new BigDecimal("0.025"),2);
        bills.append(new Gateway.Spend("test-agent-staging","qwen-plus",20,10,new BigDecimal("0.005"),"in-flight",Instant.now(),2,false,"SETTLED",BigDecimal.ZERO,"partial-attempt","trace","v1","primary",0));
        var view=costs.inspect(id);assertThat(view.get("costState")).isEqualTo("pending");
        assertThat(new BigDecimal(view.get("reservedCny").toString())).isEqualByComparingTo("0.02");
        assertThatThrownBy(()->costs.admit("test-agent-staging",ticket,"too-early","interactive",new BigDecimal("0.01"),1)).isInstanceOf(Gateway.GatewayException.class);
        costs.reconcileRequest("in-flight");
        costs.admit("test-agent-staging",ticket,"after-completion","interactive",new BigDecimal("0.01"),1);
    }
}
