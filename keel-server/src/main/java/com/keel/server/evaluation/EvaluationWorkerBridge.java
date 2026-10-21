package com.keel.server.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.auth.*;
import com.keel.server.tool.service.EmployeeReadService;
import com.keel.server.common.KeelException;
import com.keel.server.common.R;
import com.keel.server.integration.langfuse.LangfuseClient;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

/** Run-scoped delegation; an agent service assertion alone never grants user access. */
@Service
public class EvaluationWorkerBridge {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final KubernetesClient cluster;
    private final UserReadContext contexts;
    private final ConsoleUsers users;
    private final LangfuseClient langfuse;
    public EvaluationWorkerBridge(JdbcTemplate jdbc,ObjectMapper json,KubernetesClient cluster,
                                  UserReadContext contexts,ConsoleUsers users,LangfuseClient langfuse) {
        this.jdbc=jdbc;this.json=json;this.cluster=cluster;this.contexts=contexts;this.users=users;this.langfuse=langfuse;
    }
    public void initialize(String id,String namespace,ConsolePrincipal user) {
        requireUser(user);
        var bytes=new byte[48];new SecureRandom().nextBytes(bytes);
        var capability=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        cluster.secrets().inNamespace(namespace).resource(new SecretBuilder().withNewMetadata()
                .withName(secretName(id)).addToLabels("keel.io/eval-run",id).endMetadata()
                .addToStringData("capability",capability).build()).create();
        try {jdbc.update("UPDATE eval_run SET runner='acceptance-v1',context_user=?::jsonb,capability_hash=? WHERE run_id=?",
                encode(user),hash(capability),id);}
        catch(RuntimeException failure){cluster.secrets().inNamespace(namespace).withName(secretName(id)).delete();throw failure;}
    }
    public static String secretName(String id){return "keel-eval-auth-"+id.replace('_','-');}
    static void requireUser(ConsolePrincipal user) {
        if(user==null || !"USER".equals(user.mode()) || user.readOnly() || user.userId()==null || user.userId().isBlank()
                || !"ADMIN".equals(user.platformRole()))throw denied();
    }
    private State authorize(String id,String capability) {
        var caller=ConsolePrincipal.current();
        if(caller==null || !"SERVICE".equals(caller.mode()) || capability==null || capability.length()!=64)throw denied();
        var rows=jdbc.query("SELECT agent_name,env,namespace,image,endpoint,dataset,context_user::text,capability_hash,state,created_at FROM eval_run WHERE run_id=? AND runner='acceptance-v1'",(rs,n)->{
            try{return new State(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),
                    json.readValue(rs.getString(7),ConsolePrincipal.class),rs.getString(8),rs.getString(9),rs.getTimestamp(10).toInstant());}
            catch(Exception error){throw denied();}},id);
        if(rows.size()!=1)throw denied();var run=rows.getFirst();
        if(!caller.username().equals(run.agent) || !"staging".equals(run.env)
                || !Set.of("QUEUED","RUNNING").contains(run.state) || run.created.plusSeconds(3600).isBefore(Instant.now())
                || run.hash==null || !MessageDigest.isEqual(hash(capability).getBytes(StandardCharsets.US_ASCII),run.hash.getBytes(StandardCharsets.US_ASCII)))throw denied();
        requireUser(run.user);
        var current=users.find(run.user.username()).orElseThrow(EvaluationWorkerBridge::denied);
        if(!current.platformRole().equals(run.user.platformRole()) || !current.org().equals(run.user.org()))throw denied();
        return run;
    }
    public Map<String,Object> context(String id,String cap,JsonNode body) {
        var run=authorize(id,cap);
        var snapshot=snapshot(id,run.dataset);
        if(body.path("bootstrap").asBoolean())return Map.of("dataset",run.dataset,"datasetHash",hash(encode(snapshot)),"items",snapshot);
        var caseId=caseId(body);var turn=turn(body);
        if(snapshot.stream().noneMatch(r->caseId.equals(r.path("metadata").path("caseId").asText())))throw denied();
        var group=group(body);var rawSession=id+":"+caseId+":"+group;
        var ticket=contexts.issue(run.user,run.agent,run.env,rawSession);
        if(ticket==null)throw denied();
        var history=new ArrayList<Map<String,String>>();
        var previous=jdbc.query("SELECT question,answer FROM eval_turn WHERE run_id=? AND case_id=? AND session_group=? AND turn_index<? AND answer IS NOT NULL ORDER BY turn_index DESC LIMIT 3",
                (rs,n)->List.of(Map.of("role","user","content",clip(rs.getString(1))),Map.of("role","assistant","content",clip(rs.getString(2)))),id,caseId,group,turn);
        Collections.reverse(previous);previous.forEach(history::addAll);
        var output=new LinkedHashMap<String,Object>();output.put("readContext",ticket);
        output.put("sessionId",UserReadContext.sessionHash(run.user.userId(),rawSession));output.put("history",history);output.put("endpoint",run.endpoint);
        var approved=Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_employee_read_scope WHERE agent_name=? AND environment=? AND target_org=? AND enabled)",Boolean.class,run.agent,run.env,run.user.org()));
        output.put("policyEvidence",Map.of("source","Keel current read authorization and environment policy", "scopeOrg",run.user.org(),
                "employeeEnvironments",approved ? EmployeeReadService.READ_ENVIRONMENTS.stream().sorted().toList() : List.of(),
                "retiredEnvironments",EmployeeReadService.RETIRED_ENVIRONMENTS.stream().sorted().toList(),
                "businessEnvironments",EmployeeReadService.BUSINESS_ENVIRONMENTS.stream().sorted().toList()));
        if(body.path("alternateInstance").asBoolean()) {
            var pods=cluster.pods().inNamespace(run.namespace).withLabel("keel.io/agent",run.agent).list().getItems().stream()
                    .filter(p->run.env.equals(p.getMetadata().getLabels().get("keel.io/env")))
                    .filter(p->EvalJobs.ready(p) && run.image.equals(p.getSpec().getContainers().getFirst().getImage()))
                    .sorted(Comparator.comparing(p->p.getMetadata().getName())).toList();
            if(pods.size()<2)return Map.of("blocked","需要两个相同镜像的Ready实例");
            var pod=pods.get(turn%2);var port=8000;
            output.put("endpoint","http://"+pod.getStatus().getPodIP()+":"+port);output.put("instanceId",pod.getMetadata().getName());
        }
        return output;
    }
    public void saveTurn(String id,String cap,JsonNode body) {
        var run=authorize(id,cap);var caseId=caseId(body);var turn=turn(body);
        if(snapshot(id,run.dataset).stream().noneMatch(r->caseId.equals(r.path("metadata").path("caseId").asText())))throw denied();
        var question=body.path("question").asText();var answer=body.path("answer").isTextual()?body.path("answer").asText():null;
        if(question.length()>10000 || (answer!=null && answer.length()>40000) || encode(body).length()>1000000)throw denied();
        jdbc.update("INSERT INTO eval_turn(run_id,case_id,turn_index,session_group,question,answer,evidence) VALUES (?,?,?,?,?,?,?::jsonb) ON CONFLICT DO NOTHING",
                id,caseId,turn,group(body),question,answer,encode(body.path("evidence")));
    }
    public void progress(String id,String cap,JsonNode result) {
        var run=authorize(id,cap);
        var frozen=snapshot(id,run.dataset);
        validateProgress(result,id,run.agent,run.dataset,frozen);
        if(!hash(encode(frozen)).equals(result.path("datasetHash").asText()))throw denied();
        jdbc.update("UPDATE eval_run SET state='RUNNING',progress_result=?::jsonb,updated_at=now() WHERE run_id=? AND state IN ('QUEUED','RUNNING')",encode(result),id);
    }
    static void validateProgress(JsonNode result,String id,String agent,String dataset,List<JsonNode> frozen) {
        var expected=new HashSet<String>();frozen.forEach(r->expected.add(r.path("metadata").path("caseId").asText()));
        var seen=new HashSet<String>();
        for(var row:result.path("cases"))if(!expected.contains(row.path("caseId").asText()) || !seen.add(row.path("caseId").asText()))throw denied();
        if(!seen.equals(expected) || !agent.equals(result.path("agent").asText()) || !id.equals(result.path("candidateVersion").asText())
                || !dataset.equals(result.path("dataset").asText()) || !"acceptance-v1".equals(result.path("runner").asText()))throw denied();
        var pending=false;var criticalFailure=false;var executed=0;var counts=new HashMap<String,Integer>();
        for(var row:result.path("cases")) {
            var status=row.path("status").asText();
            if(!Set.of("PASS","FAIL","BLOCKED","REVIEW_REQUIRED","NOT_RUN").contains(status))throw denied();
            counts.merge(status,1,Integer::sum);
            if(row.path("executed").asBoolean())executed++;
            if(!Set.of("PASS","FAIL").contains(status))pending=true;
            var source=frozen.stream().filter(r->row.path("caseId").asText().equals(r.path("metadata").path("caseId").asText())).findFirst().orElseThrow();
            if("critical".equals(source.path("metadata").path("severity").asText()) && !"PASS".equals(status))criticalFailure=true;
        }
        var score=(double)counts.getOrDefault("PASS",0)/frozen.size();
        if(!result.path("scoreTotal").isNumber() || Math.abs(result.path("scoreTotal").asDouble()-score)>0.0000001
                || (result.path("passed").asBoolean() && score<0.85))throw denied();
        var coverage=result.path("coverage");
        if(coverage.path("total").asInt()!=frozen.size() || coverage.path("executed").asInt()!=executed
                || coverage.path("passed").asInt()!=counts.getOrDefault("PASS",0) || coverage.path("failed").asInt()!=counts.getOrDefault("FAIL",0)
                || coverage.path("blocked").asInt()!=counts.getOrDefault("BLOCKED",0) || coverage.path("reviewRequired").asInt()!=counts.getOrDefault("REVIEW_REQUIRED",0))throw denied();
        if(result.path("caseCount").asInt()!=frozen.size() || (result.path("complete").asBoolean() && executed!=frozen.size()) || (result.path("complete").asBoolean() && pending)
                || (result.path("passed").asBoolean() && (pending || criticalFailure || !result.path("complete").asBoolean())))throw denied();
    }
    private List<JsonNode> snapshot(String id,String dataset) {
        var saved=jdbc.queryForObject("SELECT case_snapshot::text FROM eval_run WHERE run_id=?",String.class,id);
        if(saved==null) {
            var items=langfuse.datasetItemsComplete(dataset);
            var rows=new ArrayList<JsonNode>();for(var item:items)if("ACTIVE".equals(item.path("status").asText())) {
                var row=json.createObjectNode();row.set("input",item.path("input"));row.set("expectedOutput",item.path("expectedOutput"));row.set("metadata",item.path("metadata"));rows.add(row);
            }
            rows.sort(Comparator.comparing(r->r.path("metadata").path("caseId").asText()));
            if(rows.isEmpty() || rows.size()>200 || rows.stream().map(r->r.path("metadata").path("caseId").asText()).distinct().count()!=rows.size())throw denied();
            jdbc.update("UPDATE eval_run SET case_snapshot=?::jsonb WHERE run_id=? AND case_snapshot IS NULL",encode(rows),id);
            saved=jdbc.queryForObject("SELECT case_snapshot::text FROM eval_run WHERE run_id=?",String.class,id);
        }
        try {var rows=new ArrayList<JsonNode>();json.readTree(saved).forEach(rows::add);return rows;}
        catch(Exception error){throw denied();}
    }
    static String hash(String value) {try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception error){throw new IllegalStateException(error);}}
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception error){throw new IllegalStateException(error);}}
    private static String caseId(JsonNode body){var id=body.path("caseId").asText();if(!id.matches("[a-zA-Z0-9_.-]{1,128}"))throw denied();return id;}
    private static int turn(JsonNode body){var n=body.path("turn");if(!n.isInt() || n.asInt()<0 || n.asInt()>9)throw denied();return n.asInt();}
    private static String group(JsonNode body){var g=body.path("sessionGroup").asText("same");if(!Set.of("same","fresh").contains(g))throw denied();return g;}
    private static String clip(String value){return value.length()>400?value.substring(0,400):value;}
    private static KeelException denied(){return new KeelException(ErrorCode.TOOL_NOT_GRANTED,"评测上下文未授权、过期或证据不完整");}
    private record State(String agent,String env,String namespace,String image,String endpoint,String dataset,ConsolePrincipal user,String hash,String state,Instant created){}
}
