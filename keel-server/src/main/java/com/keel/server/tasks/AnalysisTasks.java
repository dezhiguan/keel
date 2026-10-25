package com.keel.server.tasks;

import com.fasterxml.jackson.databind.*;
import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

/** Authoritative task state in application PostgreSQL. No runtime/admin credentials leave this service. */
@Service
public class AnalysisTasks {
    private final JdbcTemplate db;
    private final ObjectMapper json;
    private final TaskContext contexts;
    private final TransactionTemplate tx;
    public AnalysisTasks(JdbcTemplate db,ObjectMapper json,TaskContext contexts){
        this.db=db;this.json=json;this.contexts=contexts;
        this.tx=new TransactionTemplate(new DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource())));
    }
    public void requireScope(String org){
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM assistant_analysis_scope WHERE agent_name='keel-assistant' AND environment='staging' AND owner_org=? AND enabled)",Boolean.class,org)))throw TaskContext.denied();
    }
    public boolean hasReceipt(String token,String text){
        var c=contexts.verify(token,"user");
        if(!TaskContext.hash(text).equals(c.getClaim("text_hash")))throw TaskContext.denied();
        return receiptCommand(c,text)!=null;
    }
    private Map<String,Object> receiptCommand(com.nimbusds.jwt.JWTClaimsSet c,String text){
        var rows=db.queryForList("SELECT * FROM assistant_analysis_command WHERE owner_user=? AND namespace=? AND request_id=?",c.getSubject(),c.getClaim("namespace"),c.getClaim("request_id"));
        if(rows.isEmpty())return null;
        var row=rows.getFirst();
        if(!TaskContext.hash(text).equals(row.get("input_digest")))throw TaskContext.invalid("相同请求标识的内容冲突");
        return row;
    }
    public Map<String,Object> command(JsonNode args,String trace){
        var allowed=Set.of("operation","_authorization","_session","_text","task_id","material","acknowledge_duplicate_cost");
        args.fieldNames().forEachRemaining(k->{if(!allowed.contains(k))throw TaskContext.invalid("任务参数不在白名单");});
        var c=contexts.verify(args.path("_authorization").asText(""),"user");
        try{
            var user=contexts.requireUser(c.getStringClaim("username"));requireScope(user.org());
            String text=args.path("_text").asText("");
            if(text.length()>20000 || !TaskContext.hash(text).equals(c.getStringClaim("text_hash"))
                || !args.path("_session").asText("").equals(c.getStringClaim("session")))throw TaskContext.denied();
            String id=args.path("task_id").asText("");String op=args.path("operation").asText("");
            return tx.execute(status->{
                if("receipt".equals(op)){
                    var prior=receiptCommand(c,text);
                    if(prior==null)throw TaskContext.invalid("未找到此请求的持久化任务回执");
                    var result=detail(owned(c.getSubject(),String.valueOf(c.getClaim("namespace")),(String)prior.get("task_id"),false));
                    result.put("request_replayed",true);result.put("replayed_operation",prior.get("operation"));return result;
                }
                if("list".equals(op)){
                    var rows=db.queryForList("SELECT * FROM assistant_analysis_task WHERE owner_user=? AND namespace=? AND agent_name='keel-assistant' AND environment='staging' ORDER BY created_at DESC LIMIT 20",c.getSubject(),c.getClaim("namespace"));
                    return Map.of("items",rows.stream().map(this::snapshot).toList(),"limit",20);
                }
                if("get".equals(op))return detail(commandTask(c,text,id));
                if(!Set.of("create","prepare","supply","cancel","retry").contains(op))throw TaskContext.invalid("不支持的任务动作");
                String request=(String)c.getClaim("request_id"),digest=TaskContext.hash(text);
                db.queryForList("SELECT pg_advisory_xact_lock(hashtextextended(?,0))",c.getSubject()+":"+c.getClaim("namespace")+":"+request);
                var prior=db.queryForList("SELECT * FROM assistant_analysis_command WHERE owner_user=? AND namespace=? AND request_id=?",c.getSubject(),c.getClaim("namespace"),request);
                if(!prior.isEmpty()){
                    var command=prior.getFirst();
                    if(!digest.equals(command.get("input_digest"))||!op.equals(command.get("operation")))throw TaskContext.invalid("相同请求标识的内容或操作冲突");
                    if(!Set.of("create","prepare").contains(op)&&!id.equals(command.get("task_id")))throw TaskContext.invalid("相同请求标识的任务冲突");
                    return snapshot(owned(c.getSubject(),String.valueOf(c.getClaim("namespace")),(String)command.get("task_id"),false));
                }
                Map<String,Object> result;
                if(Set.of("create","prepare").contains(op))result=create(c,args,text,user.org(),trace,"prepare".equals(op));
                else result=change(c,args,text,id,op);
                var taskId=(String)result.get("task_id");
                var reference=db.queryForObject("SELECT authorization_ref FROM assistant_analysis_task WHERE task_id=?",String.class,taskId);
                db.update("INSERT INTO assistant_analysis_command(owner_user,namespace,request_id,operation,input_digest,task_id,authorization_ref,trace_id) VALUES (?,?,?,?,?,?,?,?)",c.getSubject(),c.getClaim("namespace"),request,op,digest,taskId,reference,trace);
                return result;
            });
        }catch(java.text.ParseException e){throw TaskContext.denied();}
    }
    private Map<String,Object> commandTask(com.nimbusds.jwt.JWTClaimsSet c,String text,String id){
        if(!id.matches("task_[a-f0-9]{32}"))throw TaskContext.invalid("请提供准确的任务ID");
        if(!text.contains(id) && db.queryForObject("SELECT count(*) FROM assistant_analysis_task WHERE owner_user=? AND namespace=? AND agent_name='keel-assistant' AND environment='staging'",Integer.class,c.getSubject(),c.getClaim("namespace"))>1)
            throw TaskContext.invalid("存在多个任务，请在消息中明确任务ID，不能猜测它或刚才那个");
        return owned(c.getSubject(),String.valueOf(c.getClaim("namespace")),id,true);
    }
    private Map<String,Object> change(com.nimbusds.jwt.JWTClaimsSet c,JsonNode args,String text,String id,String operation){
        var task=commandTask(c,text,id);String state=(String)task.get("state");
        if("supply".equals(operation)){
            if(!"waiting_input".equals(state))throw TaskContext.invalid("仅待补充任务可补充材料；更改目标须另建任务");
            var material=args.path("material").asText("").strip();requireMaterial(material,text);
            var directive=text.replace(material,"[用户提供的文字材料]");requireNotDiscussion(directive);
            if(directive.matches("(?s).*(修改目标|更改目标|改变目标|目标改为).*"))throw TaskContext.invalid("不能覆盖原目标，请另建明确的新任务");
            db.update("UPDATE assistant_analysis_task SET material=?,state='queued',revision=revision+1,authorization_ref=?,authorized_until=clock_timestamp()+interval '15 minutes',updated_at=clock_timestamp() WHERE task_id=?",material,grantReference(),id);
        }else if("cancel".equals(operation)){
            if(!text.matches("(?s).*(取消|停止|终止).*"))throw TaskContext.invalid("取消任务需要用户明确指令");
            requireNotDiscussion(text);
            if(Set.of("waiting_input","queued","waiting_authorization").contains(state))state="cancelled";
            else if(Set.of("running","validating").contains(state))state="cancel_requested";
            db.update("UPDATE assistant_analysis_task SET state=?,updated_at=clock_timestamp() WHERE task_id=?",state,id);
        }else{
            if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM assistant_analysis_command WHERE task_id=? AND operation='cancel')",Boolean.class,id)))
                throw TaskContext.invalid("该任务已有取消请求，不能重新受理重试；此前调用及费用仍可能未确认");
            if(!Set.of("failed","unknown","waiting_authorization").contains(state)||((Number)task.get("attempt")).intValue()>=2||((String)task.get("material")).isBlank())throw TaskContext.invalid("任务当前不可重试、材料缺失或已达到两次执行上限");
            if(!args.path("acknowledge_duplicate_cost").asBoolean(false)||!text.matches("(?s).*(重试|继续|恢复).*")
                ||!text.matches("(?s).*((确认|接受|同意|允许).{0,30}(费用|重复|计算)|(费用|重复|计算).{0,30}(确认|接受|同意|允许)).*"))throw TaskContext.invalid("重试需要明确确认可能重复计算和费用");
            requireNotDiscussion(text);
            db.update("UPDATE assistant_analysis_task SET state='queued',revision=revision+1,fence=fence+1,lease_owner=NULL,lease_until=NULL,intent_at=NULL,authorization_ref=?,authorized_until=clock_timestamp()+interval '15 minutes',updated_at=clock_timestamp() WHERE task_id=?",grantReference(),id);
        }
        return snapshot(owned(c.getSubject(),String.valueOf(c.getClaim("namespace")),id,false));
    }
    private static String grantReference(){return "grant_"+UUID.randomUUID().toString().replace("-","");}
    private static void requireMaterial(String material,String text){
        if(material.isEmpty()||material.length()>12000||!text.contains(material))throw TaskContext.invalid("请直接提供不超过12000字的完整文字材料");
        if(material.matches("(?s)(链接[:：]\\s*)?https?://\\S+"))throw TaskContext.invalid("本版不读取链接，请直接粘贴要分析的文字材料");
    }
    private static void requireNotDiscussion(String directive){
        if(directive.matches("(?s).*(是否支持|能否支持|讨论|假设).*")||directive.matches("(?s)\\s*(如何|怎么|是什么|什么是|如果).*"))throw TaskContext.invalid("当前是在讨论方案，请明确实际任务指令");
        if(directive.matches("(?s).*(不要|暂不|先不|先别|不需要|不必).{0,20}(生成|创建|开始|执行|分析|总结|提取|取消|重试|恢复).*"))throw TaskContext.invalid("当前消息未授权执行该动作");
    }
    private Map<String,Object> create(com.nimbusds.jwt.JWTClaimsSet c,JsonNode args,String text,String org,String trace,boolean pending){
        String requestId=(String)c.getClaim("request_id");String fingerprint=TaskContext.hash(text);
        var existing=db.queryForList("SELECT * FROM assistant_analysis_task WHERE agent_name='keel-assistant' AND environment='staging' AND owner_user=? AND namespace=? AND request_id=?",c.getSubject(),c.getClaim("namespace"),requestId);
        if(!existing.isEmpty()){
            if(!fingerprint.equals(existing.getFirst().get("input_digest")))throw TaskContext.invalid("相同请求标识携带不同任务内容，拒绝冲突");
            return snapshot(existing.getFirst());
        }
        var material=pending?"":args.path("material").asText("").strip();
        if(!pending)requireMaterial(material,text);
        var objective=(pending?text:text.replace(material,"[用户提供的文字材料]")).strip();
        // The directive is outside the quoted material, so instructions inside data cannot create tasks.
        if(objective.length()>2000 || !objective.matches("(?s).*(生成|创建|开始|执行|分析|总结|提取).*(报告|任务|风险|问题|材料).*"))
            throw TaskContext.invalid("需要明确的资料分析任务目标");
        requireNotDiscussion(objective);
        String id="task_"+UUID.randomUUID().toString().replace("-","");
        var inserted=db.queryForList("""
            INSERT INTO assistant_analysis_task(task_id,agent_name,environment,owner_user,owner_username,owner_org,
              session_id,request_id,namespace,input_digest,original_goal,material,authorization_ref,authorized_until,state,parent_trace_id)
            VALUES (?,'keel-assistant','staging',?,?,?,?,?,?,?,?,?,?,clock_timestamp()+interval '15 minutes',?,?)
            ON CONFLICT(agent_name,environment,owner_user,namespace,request_id) DO NOTHING RETURNING *
            """,id,c.getSubject(),c.getClaim("username"),org,c.getClaim("session"),requestId,c.getClaim("namespace"),fingerprint,objective,material,grantReference(),pending?"waiting_input":"queued",trace);
        if(!inserted.isEmpty())return snapshot(inserted.getFirst());
        var prior=db.queryForMap("SELECT * FROM assistant_analysis_task WHERE agent_name='keel-assistant' AND environment='staging' AND owner_user=? AND namespace=? AND request_id=?",c.getSubject(),c.getClaim("namespace"),requestId);
        if(!fingerprint.equals(prior.get("input_digest")))throw TaskContext.invalid("相同请求标识携带不同任务内容，拒绝冲突");
        return snapshot(prior);
    }
    private Map<String,Object> owned(String owner,String namespace,String id,boolean lock){
        var rows=db.queryForList("SELECT * FROM assistant_analysis_task WHERE task_id=? AND owner_user=? AND namespace=? AND agent_name='keel-assistant' AND environment='staging'"+(lock?" FOR UPDATE":""),id,owner,namespace);
        if(rows.isEmpty())throw new KeelException(ErrorCode.SERVER_NOT_FOUND,"当前用户没有该任务");return rows.getFirst();
    }
    public Map<String,Object> snapshot(Map<String,Object> row){
        var out=new LinkedHashMap<String,Object>();
        for(String name:List.of("task_id","state","original_goal","revision","attempt","created_at","updated_at","report","acceptance","error","parent_trace_id","execution_trace_id","execution_run_id","prompt_version","gateway_request_id","cost_cny","usage")){
            Object v=row.get(name);out.put(name,v instanceof Timestamp t?t.toInstant().toString():v);
            if(v!=null && Set.of("acceptance","usage").contains(name))try{out.put(name,json.readTree(v.toString()));}catch(Exception invalid){out.put(name,null);}
        }
        out.put("executor","keel-assistant");out.put("environment","staging");out.put("task_type","analysis");
        out.put("cancellation_note","cancel_requested".equals(row.get("state"))?"已请求停止后续步骤；正在进行的模型调用未确认停止，可能产生费用":
            "unknown".equals(row.get("state"))?"调用结果未知；不会自动重算，可能已产生计算与费用":
            "cancelled".equals(row.get("state"))?((Number)row.get("attempt")).intValue()==0?"在执行前取消，没有发起模型调用":
            "后续步骤已取消；先前调用不保证已停止，已有计算和费用不能撤回，请回查历次执行记录":null);
        if("unknown".equals(row.get("state")) && Boolean.TRUE.equals(db.queryForObject(
                "SELECT EXISTS(SELECT 1 FROM assistant_analysis_command WHERE task_id=? AND operation='cancel')",Boolean.class,row.get("task_id"))))
            out.put("cancellation_note","已请求取消；此前调用结果及费用仍未知，不会自动重算，也不允许重新受理重试");
        return out;
    }
    private Map<String,Object> detail(Map<String,Object> row){
        var out=snapshot(row);
        var history=db.queryForList("SELECT attempt,state,intent_at,report,acceptance,error,execution_trace_id,execution_run_id,prompt_version,gateway_request_id,cost_cny,usage FROM assistant_analysis_attempt WHERE task_id=? ORDER BY attempt LIMIT 2",row.get("task_id"));
        var attempts=new ArrayList<Map<String,Object>>();
        for(var attempt:history){
            var item=new LinkedHashMap<String,Object>();
            for(var entry:attempt.entrySet()){
                Object value=entry.getValue();
                if(value instanceof Timestamp time)value=time.toInstant().toString();
                if(value!=null && Set.of("acceptance","usage").contains(entry.getKey()))try{value=json.readTree(value.toString());}catch(Exception invalid){value=null;}
                item.put(entry.getKey(),value);
            }
            attempts.add(item);
        }
        out.put("attempts",attempts);
        var revisions=db.queryForList("SELECT operation,trace_id,created_at FROM assistant_analysis_command WHERE task_id=? ORDER BY created_at",row.get("task_id"));
        for(var revision:revisions)if(revision.get("created_at") instanceof Timestamp time)revision.put("created_at",time.toInstant().toString());
        out.put("revisions",revisions);return out;
    }
    public void checkAuthority(Map<String,Object> row){
        var user=contexts.requireUser((String)row.get("owner_username"));requireScope(user.org());
        if(!user.org().equals(row.get("owner_org")) || !((Timestamp)row.get("authorized_until")).toInstant().isAfter(Instant.now()))throw TaskContext.denied();
        if(!Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM assistant_analysis_command WHERE task_id=? AND owner_user=? AND authorization_ref=? AND operation IN ('create','supply','retry') AND ?<=created_at+interval '15 minutes')",Boolean.class,row.get("task_id"),row.get("owner_user"),row.get("authorization_ref"),row.get("authorized_until"))))throw TaskContext.denied();
    }
    public Map<String,Object> claim(String worker){
        return tx.execute(status->{
            if(!Boolean.TRUE.equals(db.queryForObject("SELECT pg_try_advisory_xact_lock(7463301)",Boolean.class)))return null;
            db.update("""
                UPDATE assistant_analysis_task SET state=CASE WHEN intent_at IS NULL AND state<>'cancel_requested' THEN 'queued'
                  WHEN intent_at IS NULL THEN 'cancelled' ELSE 'unknown' END,
                  fence=fence+1,lease_owner=NULL,lease_until=NULL,error='Worker租约过期；有调用意图时不自动重算',updated_at=clock_timestamp()
                WHERE state IN ('running','validating','cancel_requested') AND lease_until<=clock_timestamp()
                """);
            db.update("UPDATE assistant_analysis_attempt a SET state='unknown',error=t.error FROM assistant_analysis_task t WHERE t.task_id=a.task_id AND t.attempt=a.attempt AND t.state='unknown' AND a.state IN ('running','model_starting','validating')");
            if(Boolean.TRUE.equals(db.queryForObject("SELECT EXISTS(SELECT 1 FROM assistant_analysis_task WHERE state IN ('running','validating','cancel_requested') AND lease_until>clock_timestamp())",Boolean.class)))return null;
            var rows=db.queryForList("SELECT * FROM assistant_analysis_task WHERE state='queued' ORDER BY created_at FOR UPDATE SKIP LOCKED LIMIT 1");
            if(rows.isEmpty())return null;var task=rows.getFirst();
            try{checkAuthority(task);}catch(KeelException denied){
                db.update("UPDATE assistant_analysis_task SET state='waiting_authorization',error='任务授权过期或已撤销，需要用户重新授权',updated_at=clock_timestamp() WHERE task_id=?",task.get("task_id"));return null;
            }
            return db.queryForMap("UPDATE assistant_analysis_task SET state='running',fence=fence+1,lease_owner=?,lease_until=clock_timestamp()+interval '30 seconds',updated_at=clock_timestamp() WHERE task_id=? RETURNING *",worker,task.get("task_id"));
        });
    }
    public Map<String,Object> start(Map<String,Object> claimed){
        return tx.execute(status->{
            var row=leased(claimed);checkAuthority(row);
            if(!"running".equals(row.get("state")) || ((Number)row.get("attempt")).intValue()>=2)throw TaskContext.denied();
            var task=db.queryForMap("UPDATE assistant_analysis_task SET intent_at=clock_timestamp(),attempt=attempt+1,report=NULL,acceptance=NULL,error=NULL,execution_trace_id=NULL,execution_run_id=NULL,prompt_version=NULL,gateway_request_id=NULL,cost_cny=NULL,usage=NULL WHERE task_id=? RETURNING *",row.get("task_id"));
            db.update("INSERT INTO assistant_analysis_attempt(task_id,attempt,fence,state) VALUES (?,?,?,'running')",task.get("task_id"),task.get("attempt"),task.get("fence"));return task;
        });
    }
    private Map<String,Object> leased(Map<String,Object> task){
        var rows=db.queryForList("SELECT * FROM assistant_analysis_task WHERE task_id=? AND fence=? AND lease_owner=? AND lease_until>clock_timestamp() FOR UPDATE",task.get("task_id"),task.get("fence"),task.get("lease_owner"));
        if(rows.isEmpty())throw TaskContext.denied();return rows.getFirst();
    }
    public boolean renew(Map<String,Object> task){
        return db.update("UPDATE assistant_analysis_task SET lease_until=clock_timestamp()+interval '30 seconds' WHERE task_id=? AND fence=? AND lease_owner=? AND lease_until>clock_timestamp() AND state IN ('running','validating','cancel_requested')",task.get("task_id"),task.get("fence"),task.get("lease_owner"))==1;
    }
    public void abandonBeforeIntent(Map<String,Object> task){
        tx.executeWithoutResult(status->{var row=leased(task);
            if(row.get("intent_at")!=null)throw TaskContext.denied();
            db.update("UPDATE assistant_analysis_task SET state=?,lease_owner=NULL,lease_until=NULL,error=?,updated_at=clock_timestamp() WHERE task_id=?",
                "cancel_requested".equals(row.get("state"))?"cancelled":"waiting_authorization",
                "执行前授权无效或收到取消；没有发起模型调用",row.get("task_id"));
        });
    }
    public Map<String,Object> execution(String ticket,String trace,String runId){
        return execution(ticket,trace,runId,"load");
    }
    public Map<String,Object> execution(String ticket,String trace,String runId,String phase){
        var c=contexts.verify(ticket,"execution");
        try{return tx.execute(status->{
            var rows=db.queryForList("SELECT * FROM assistant_analysis_task WHERE task_id=? AND owner_user=? AND fence=? AND lease_until>clock_timestamp() AND state='running' FOR UPDATE",c.getClaim("task_id"),c.getSubject(),c.getClaim("fence"));
            if(rows.isEmpty())throw TaskContext.denied();var row=rows.getFirst();checkAuthority(row);
            if(!runId.matches("[a-f0-9]{32}") || !trace.matches("[a-f0-9]{32}"))throw TaskContext.denied();
            if("begin_model".equals(phase)){
                if(!runId.equals(row.get("execution_run_id"))||!trace.equals(row.get("execution_trace_id")))throw TaskContext.denied();
                if(db.update("UPDATE assistant_analysis_attempt SET state='model_starting' WHERE task_id=? AND attempt=? AND fence=? AND state='running'",row.get("task_id"),row.get("attempt"),row.get("fence"))!=1)throw TaskContext.denied();
                return Map.of("task_id",row.get("task_id"),"allowed",true);
            }
            if(!"load".equals(phase)||row.get("execution_run_id")!=null)throw TaskContext.denied(); // no duplicate execution of this attempt
            db.update("UPDATE assistant_analysis_task SET execution_trace_id=?,execution_run_id=? WHERE task_id=?",trace,runId,row.get("task_id"));
            db.update("UPDATE assistant_analysis_attempt SET execution_trace_id=?,execution_run_id=? WHERE task_id=? AND attempt=?",trace,runId,row.get("task_id"),row.get("attempt"));
            return Map.of("task_id",row.get("task_id"),"goal",row.get("original_goal"),"material",row.get("material"),"source_id","material","max_seconds",120);
        });}catch(Exception denied){throw TaskContext.denied();}
    }
    public void finish(Map<String,Object> task,String raw,Integer promptVersion,String error){
        finish(task,raw,promptVersion,error,List.of());
    }
    public void finish(Map<String,Object> task,String raw,Integer promptVersion,String error,List<String> additionalFailures){
        if(error==null)tx.executeWithoutResult(status->{
            var row=leased(task);
            var saved=raw==null?null:raw.substring(0,Math.min(raw.length(),100000));
            db.update("UPDATE assistant_analysis_task SET state=?,report=?,prompt_version=?,updated_at=clock_timestamp() WHERE task_id=?",
                "cancel_requested".equals(row.get("state"))?"cancel_requested":"validating",saved,promptVersion,task.get("task_id"));
            db.update("UPDATE assistant_analysis_attempt SET state='validating',report=?,prompt_version=? WHERE task_id=? AND attempt=?",saved,promptVersion,task.get("task_id"),task.get("attempt"));
        });
        tx.executeWithoutResult(status->{
            var row=leased(task);var checks=new ArrayList<>(validate(raw,(String)row.get("material")));checks.addAll(additionalFailures);
            String state=error!=null?"unknown":checks.isEmpty()?"succeeded":"failed";
            if("cancel_requested".equals(row.get("state")) && error==null)state="cancelled";
            var acceptance=new LinkedHashMap<String,Object>();acceptance.put("passed",checks.isEmpty() && error==null);acceptance.put("errors",checks);acceptance.put("semantic_review","pending");
            acceptance.put("artifact_sha256",raw==null?null:TaskContext.hash(raw));acceptance.put("truncated",raw!=null && raw.length()>100000);
            String report=raw==null?null:raw.substring(0,Math.min(raw.length(),100000));
            String why=error!=null?error:checks.isEmpty()?null:String.join("；",checks);
            db.update("UPDATE assistant_analysis_task SET state=?,report=?,acceptance=?::jsonb,error=?,prompt_version=?,lease_owner=NULL,lease_until=NULL,updated_at=clock_timestamp() WHERE task_id=?",state,report,encode(acceptance),why,promptVersion,task.get("task_id"));
            db.update("UPDATE assistant_analysis_attempt SET state=?,report=?,acceptance=?::jsonb,error=?,prompt_version=? WHERE task_id=? AND attempt=?",state,report,encode(acceptance),why,promptVersion,task.get("task_id"),task.get("attempt"));
        });
    }
    public List<String> validate(String raw,String material){
        var errors=new ArrayList<String>();
        try{
            if(raw==null || raw.isBlank() || raw.length()>100000)return List.of("报告缺失或超过长度限制");
            var report=json.copy().enable(DeserializationFeature.FAIL_ON_READING_DUP_TREE_KEY).readTree(raw);
            if(report.size()!=2 || !report.path("summary").isTextual() || report.path("summary").asText().isBlank()
                || !report.path("risks").isArray() || report.path("risks").size()!=3)return List.of("报告必须含主要内容总结和三个问题/风险");
            var names=new HashSet<String>();
            for(var risk:report.path("risks")){
                var ev=risk.path("evidence");
                if(risk.size()!=3 || !risk.path("risk").isTextual() || risk.path("risk").asText().isBlank()
                    || risk.path("risk").asText().length()>4000 || risk.path("recommendation").asText().length()>4000
                    || !names.add(risk.path("risk").asText().strip().toLowerCase(Locale.ROOT)) || !risk.path("recommendation").isTextual()
                    || risk.path("recommendation").asText().isBlank())errors.add("风险或改进建议缺失、重复或格式错误");
                if(ev.size()!=2 || !"material".equals(ev.path("source_id").asText()) || !ev.path("quote").isTextual()
                    || ev.path("quote").asText().isBlank() || !material.contains(ev.path("quote").asText()))errors.add("依据不对应用户原始文字材料");
            }
        }catch(Exception invalid){errors.add("报告不是有效JSON");}return errors;
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception error){throw new IllegalStateException(error);}}
    @org.springframework.scheduling.annotation.Scheduled(cron="0 0 3 * * *",zone="Asia/Shanghai")
    public void expireTerminalArtifacts(){
        tx.executeWithoutResult(status->{
            db.update("DELETE FROM assistant_analysis_command WHERE task_id IN (SELECT task_id FROM assistant_analysis_task WHERE state IN ('waiting_input','succeeded','failed','cancelled') AND updated_at<clock_timestamp()-interval '30 days')");
            db.update("DELETE FROM assistant_analysis_attempt WHERE task_id IN (SELECT task_id FROM assistant_analysis_task WHERE state IN ('waiting_input','succeeded','failed','cancelled') AND updated_at<clock_timestamp()-interval '30 days')");
            db.update("DELETE FROM assistant_analysis_task WHERE state IN ('waiting_input','succeeded','failed','cancelled') AND updated_at<clock_timestamp()-interval '30 days'");
        });
    }
}
