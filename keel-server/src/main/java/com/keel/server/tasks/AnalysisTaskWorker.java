package com.keel.server.tasks;

import com.fasterxml.jackson.databind.*;
import com.keel.server.registry.service.AgentRegistryService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import jakarta.annotation.PreDestroy;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.LoggerFactory;

/** Durable queue dispatcher. All analysis/model business runs in the same assistant's SDK entry. */
@Component
public class AnalysisTaskWorker {
    private final AnalysisTasks tasks;private final TaskContext contexts;private final AgentRegistryService registry;
    private final ObjectMapper json;private final AtomicBoolean busy=new AtomicBoolean();
    private final String worker=UUID.randomUUID().toString();
    private final ExecutorService executor=Executors.newSingleThreadExecutor(r->{var t=new Thread(r,"assistant-analysis-worker");t.setDaemon(true);return t;});
    private final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(r->{var t=new Thread(r,"assistant-analysis-lease");t.setDaemon(true);return t;});
    private final HttpClient http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    public AnalysisTaskWorker(AnalysisTasks tasks,TaskContext contexts,AgentRegistryService registry,ObjectMapper json){this.tasks=tasks;this.contexts=contexts;this.registry=registry;this.json=json;}
    @Scheduled(fixedDelay=1000)
    public void poll(){
        if(!busy.compareAndSet(false,true))return;
        try{var task=tasks.claim(worker);if(task==null){busy.set(false);return;}executor.submit(()->run(task));}
        catch(Exception error){busy.set(false);LoggerFactory.getLogger(getClass()).warn("trace_id=none agent=keel-assistant task queue unavailable");}
    }
    private void run(Map<String,Object> claimed){
        Map<String,Object> task=null;ScheduledFuture<?> heartbeat=null;var valid=new AtomicBoolean(true);
        try{
            try{task=tasks.start(claimed);}catch(Exception denied){tasks.abandonBeforeIntent(claimed);return;}
            var active=task;
            heartbeat=timer.scheduleAtFixedRate(()->{try{if(!tasks.renew(active))valid.set(false);}catch(Exception unavailable){valid.set(false);}},5,5,TimeUnit.SECONDS);
            String endpoint=registry.invokeEndpoint("keel-assistant");
            if(endpoint.contains("//echo-agent."))throw new IllegalStateException("independent assistant endpoint required");
            var payload=Map.of("input",Map.of("text","执行已授权的文字分析任务","_keel_task_execution",contexts.issueExecution(task)),
                "context",Map.of("user_id",task.get("owner_user")));
            var builder=HttpRequest.newBuilder(URI.create(endpoint.replaceAll("/$","")+"/v1/invoke"))
                .timeout(Duration.ofSeconds(120)).header("Content-Type","application/json")
                .header("X-Keel-Agent","keel-assistant").header("X-Keel-Env","staging");
            if(String.valueOf(task.get("namespace")).startsWith("acceptance:"))builder.header("X-Keel-Eval-Run","task-"+String.valueOf(task.get("namespace")).substring(11));
            var request=builder.POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(payload))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new IllegalStateException("HTTP:"+response.statusCode());
            var result=readFinal(response.body());
            if(!valid.get())return;
            if(!task.get("task_id").equals(result.path("meta").path("task_id").asText())
                || !"keel-assistant".equals(result.path("meta").path("executor").asText())
                || !result.path("meta").path("analysis_prompt_fallback").isBoolean()
                || result.path("meta").path("analysis_prompt_fallback").asBoolean()
                || !result.path("meta").path("model_permit_checked").isBoolean()
                || !result.path("meta").path("model_permit_checked").asBoolean()
                || !result.path("meta").path("analysis_prompt_version").isInt())throw new IllegalStateException("task execution evidence mismatch");
            var local=result.path("meta").path("analysis_validation").path("passed");
            tasks.finish(task,result.path("answer").asText(),result.path("meta").path("analysis_prompt_version").asInt(),null,
                local.isBoolean() && local.asBoolean()?List.of():List.of("助理代码的确定性报告核验未通过或证据缺失"));
        }catch(Exception error){
            var detail=error.getMessage()!=null && error.getMessage().matches("SSE:[A-Z_]{1,80}|HTTP:[0-9]{3}")?"（"+error.getMessage()+"）":"";
            if(task!=null && valid.get())try{tasks.finish(task,null,null,"模型调用结果未确认或流被中断"+detail+"；不会自动重算，可能已有费用");}catch(Exception lost){/* stale worker cannot write */}
            LoggerFactory.getLogger(getClass()).warn("trace_id=unknown agent=keel-assistant task={} execution interrupted",claimed.get("task_id"));
        }finally{if(heartbeat!=null)heartbeat.cancel(false);busy.set(false);}
    }
    JsonNode readFinal(String wire)throws Exception{
        String event="";JsonNode terminal=null;
        for(String line:wire.split("\n")){
            if(line.startsWith("event:"))event=line.substring(6).strip();
            else if(line.startsWith("data:") && "error".equals(event)){
                var code=json.readTree(line.substring(5).strip()).path("code").asText("");
                throw new IllegalStateException(code.matches("[A-Z_]{1,80}")?"SSE:"+code:"assistant SSE error");
            }
            else if(line.startsWith("data:") && "final".equals(event))terminal=json.readTree(line.substring(5).strip());
        }
        if(terminal==null)throw new IllegalStateException("missing final");return terminal;
    }
    @PreDestroy public void close(){executor.shutdownNow();timer.shutdownNow();}
}
