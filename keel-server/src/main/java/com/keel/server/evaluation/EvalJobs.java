package com.keel.server.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.common.error.ErrorCode;
import com.keel.server.auth.ConsolePrincipal;
import com.keel.server.common.KeelException;
import com.keel.server.prompt.PromptCatalog;
import com.keel.server.registry.model.dto.AgentDetail;
import com.keel.server.registry.service.AgentRegistryService;
import com.keel.server.registry.model.enums.AgentStatus;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.slf4j.LoggerFactory;
import java.time.Instant;
import java.util.*;
import java.util.Locale;

/** Durable run records, deterministic Kubernetes Jobs, and restart-safe reconciliation. */
@Service
public class EvalJobs {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final KubernetesClient cluster;
    private final AgentRegistryService registry;
    private final PromptCatalog catalog;
    public EvalJobs(JdbcTemplate jdbc, ObjectMapper json, KubernetesClient cluster, AgentRegistryService registry, PromptCatalog catalog) {
        this.jdbc=jdbc; this.json=json; this.cluster=cluster; this.registry=registry; this.catalog=catalog;
    }

    @org.springframework.beans.factory.annotation.Autowired
    private EvaluationWorkerBridge acceptance;

    @org.springframework.transaction.annotation.Transactional
    public String start(String agent) {
        catalog.authorize(agent);
        var detail = registry.registeredDetail(agent);
        if (detail.summary().status()==AgentStatus.RETIRED || detail.summary().status()==AgentStatus.DRAFT
                || detail.resources().isEmpty() || detail.resources().stream().anyMatch(r -> !"ACTIVE".equals(r.status())))
            throw new KeelException(ErrorCode.GW_AGENT_OFFLINE, "智能体未开通或已下线");
        if (!"python".equalsIgnoreCase(detail.summary().language()))
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "评测运行镜像须提供 Python SDK eval extra 及 evals/scorers.py");
        var manifest=registry.manifestOrEmpty(agent);
        var dataset=manifest.path("spec").path("eval").path("dataset").asText("");
        if (dataset.isBlank()) throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "未声明评测数据集");
        try { baseline(agent); }
        catch (IllegalStateException error) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "当前生产发布的评测基线证据无效，请先核对发布记录");
        }
        var pods=cluster.pods().inAnyNamespace().withLabel("keel.io/agent",agent).list().getItems();
        var pod=pods.stream().filter(p -> detail.summary().env().equals(p.getMetadata().getLabels().get("keel.io/env")))
                .filter(EvalJobs::ready).findFirst().orElseThrow(() -> new KeelException(ErrorCode.GW_AGENT_OFFLINE,"没有带智能体和环境标签的就绪实例"));
        var container=pod.getSpec().getContainers().getFirst();
        var id="ev_"+UUID.randomUUID().toString().replace("-","");
        try {
            jdbc.update("""
                    INSERT INTO eval_run(run_id,agent_name,env,namespace,source_pod,image,endpoint,dataset,requested_by)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """,id,agent,detail.summary().env(),pod.getMetadata().getNamespace(),pod.getMetadata().getName(),
                    container.getImage(),registry.invokeEndpoint(agent),dataset,ConsolePrincipal.requireUsername());
        } catch (DuplicateKeyException e) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM,"该智能体已有评测正在执行，请等待完成");
        }
        if("acceptance-v1".equals(manifest.path("spec").path("eval").path("runner").asText())) {
            if(!"staging".equals(detail.summary().env())) {fail(id,"证据评测目前仅支持staging");throw new KeelException(ErrorCode.SERVER_INVALID_PARAM,"证据评测目前仅支持staging");}
            try {acceptance.initialize(id,pod.getMetadata().getNamespace(),ConsolePrincipal.current());}
            catch(RuntimeException failure){fail(id,"评测授权初始化失败");throw failure;}
        }
        return id;
    }

    public Map<String, String> active(String agent) {
        return active(agent, false);
    }

    public Map<String, String> active(String agent, boolean includeFailed) {
        var rows = jdbc.query("SELECT run_id,state FROM eval_run WHERE agent_name=?"
                        + (includeFailed ? "" : " AND state IN ('QUEUED','RUNNING')")
                        + " ORDER BY created_at DESC,run_id DESC LIMIT 1",
                (rs, n) -> Map.of("runId", rs.getString(1), "state", rs.getString(2)), agent);
        if (rows.isEmpty() || "DONE".equals(rows.getFirst().get("state"))) return null;
        return Map.of("runId", rows.getFirst().get("runId"));
    }

    public Map<String,Object> run(String id) {
        var rows=jdbc.query("SELECT state,COALESCE(progress_result,result)::text,error,agent_name,image,case_snapshot::text FROM eval_run WHERE run_id=?",(rs,n)-> {
            var body=new LinkedHashMap<String,Object>();
            body.put("runId",id); body.put("state",rs.getString(1));
            body.put("agent",rs.getString(4)); body.put("image",rs.getString(5));
            body.put("progress",Set.of("DONE","FAILED").contains(rs.getString(1))?1:0);
            var measured=withExecution(decode(rs.getString(2)),rs.getString(6));
            if(measured!=null && measured.get("coverage") instanceof Map<?,?> coverage && coverage.get("executed") instanceof Number done && coverage.get("total") instanceof Number total && total.doubleValue()>0)
                body.put("progress",done.doubleValue()/total.doubleValue());
            body.put("result",measured); body.put("error",rs.getString(3));
            return body;
        },id);
        if(rows.isEmpty()) throw new KeelException(ErrorCode.SERVER_NOT_FOUND,ErrorCode.SERVER_NOT_FOUND.message());
        return rows.getFirst();
    }

    /** Drawer row for the latest finished run. Opening the detail does not start an evaluation. */
    public AgentDetail withGate(AgentDetail detail) {
        if (detail == null || detail.selfCheck() == null || detail.summary() == null) {
            return detail;
        }
        var item = gateItem(latest(detail.summary().name()));
        var items = new ArrayList<>(detail.selfCheck().items());
        var replaced = false;
        for (var i = 0; i < items.size(); i++) {
            if ("评测门禁".equals(items.get(i).name())) {
                items.set(i, item);
                replaced = true;
            }
        }
        if (!replaced) {
            items.add(item);
        }
        var report = detail.selfCheck();
        return detail.withSelfCheck(new AgentDetail.CheckReport(report.passed(), items, report.checkedAt()));
    }

    static AgentDetail.CheckItem gateItem(Map<String, Object> result) {
        if (result == null || !(result.get("scoreTotal") instanceof Number number)) {
            return new AgentDetail.CheckItem("评测门禁", null, "未运行");
        }
        var min = 0.85;
        if (result.get("gate") instanceof Map<?, ?> gate && gate.get("minScore") instanceof Number threshold) {
            min = threshold.doubleValue();
        }
        // Match the list card / eval gates: this line is score vs minScore.
        // Release still reads eval_run.passed separately and may stay stricter.
        var score = number.doubleValue();
        var passed = score >= min;
        var mark = passed ? "≥" : "<";
        return new AgentDetail.CheckItem("评测门禁", passed,
                String.format(Locale.ROOT, "%.2f %s %.2f", score, mark, min));
    }

    public Map<String,Object> latest(String agent) {
        var rows=jdbc.query("SELECT result::text,case_snapshot::text FROM eval_run WHERE agent_name=? AND state='DONE' ORDER BY created_at DESC LIMIT 1",
                (rs,n)->withExecution(decode(rs.getString(1)),rs.getString(2)),agent);
        return rows.isEmpty()?null:rows.getFirst();
    }

    @SuppressWarnings("unchecked")
    private Map<String,Object> withExecution(Map<String,Object> result,String snapshot) {
        if(result==null || snapshot==null || !(result.get("cases") instanceof List<?> rows))return result;
        var executions=new HashMap<String,String>();
        parse(snapshot).forEach(r->executions.put(r.path("metadata").path("caseId").asText(),r.path("metadata").path("execution").asText()));
        for(var row:rows)if(row instanceof Map<?,?> item) {
            var execution=executions.get(item.get("caseId"));
            if(execution!=null)((Map<String,Object>)item).put("execution",execution);
        }
        return result;
    }

    public Map<String,Double> scores() {
        var out=new LinkedHashMap<String,Double>();
        jdbc.query("""
                SELECT DISTINCT ON (agent_name) agent_name,result::text FROM eval_run
                WHERE state='DONE' ORDER BY agent_name,created_at DESC
                """,rs->{var result=decode(rs.getString(2)); var score=result.get("scoreTotal");
                    if(score instanceof Number v) out.put(rs.getString(1),v.doubleValue());});
        return out;
    }

    @Scheduled(fixedDelay=5000, initialDelay=10000)
    public void reconcile() {
        var rows=jdbc.query("SELECT run_id,agent_name,env,namespace,source_pod,image,endpoint,dataset,state,created_at FROM eval_run WHERE state IN ('QUEUED','RUNNING') ORDER BY created_at LIMIT 20",
                (rs,n)->new Run(rs.getString(1),rs.getString(2),rs.getString(3),rs.getString(4),rs.getString(5),rs.getString(6),rs.getString(7),rs.getString(8),rs.getString(9),rs.getTimestamp(10).toInstant()));
        for(var row:rows) {
            try { reconcile(row); }
            catch(Exception e) {
                // Do not log upstream bodies, which can contain case content or credentials.
                LoggerFactory.getLogger(EvalJobs.class).warn("eval reconciliation failed trace_id= agent={} run={} error={}",row.agent,row.id,e.getClass().getSimpleName());
                if(row.created.plusSeconds(3660).isBefore(Instant.now())) fail(row.id,"评测超时或运行服务不可用");
            }
        }
    }

    private void reconcile(Run row) {
        var name=jobName(row.id);
        var job=cluster.batch().v1().jobs().inNamespace(row.namespace).withName(name).get();
        if(job==null) {
            if(!"QUEUED".equals(row.state)) { fail(row.id,"评测 Job 已丢失，未自动重复调用模型");return; }
            var detail=registry.registeredDetail(row.agent);
            if(detail.summary().status()==AgentStatus.RETIRED) { fail(row.id,"智能体已下线");return; }
            var pod=cluster.pods().inNamespace(row.namespace).withName(row.pod).get();
            if(pod==null || !row.image.equals(pod.getSpec().getContainers().getFirst().getImage())) {
                fail(row.id,"候选实例已变更，请重新发起评测");return;
            }
            String comparison;
            try { comparison = baseline(row.agent); }
            catch (IllegalStateException error) {
                fail(row.id,"当前生产发布的评测基线证据无效，请先核对发布记录");return;
            }
            var container=new ContainerBuilder(pod.getSpec().getContainers().getFirst()).withName("eval")
                    .withCommand("python","-m","keel.eval.worker").withArgs(List.of()).withPorts(List.of())
                    .withReadinessProbe(null).withLivenessProbe(null).withStartupProbe(null)
                    .addNewEnv().withName("KEEL_EVAL_RUN_ID").withValue(row.id).endEnv()
                    .addNewEnv().withName("KEEL_EVAL_ENV").withValue(row.env).endEnv()
                    .addNewEnv().withName("KEEL_EVAL_ENDPOINT").withValue(row.endpoint).endEnv()
                    .addNewEnv().withName("KEEL_EVAL_DATASET").withValue(row.dataset).endEnv()
                    .addNewEnv().withName("KEEL_EVAL_BASELINE").withValue(comparison).endEnv().build();
            var runner=jdbc.queryForObject("SELECT runner FROM eval_run WHERE run_id=?",String.class,row.id);
            if("acceptance-v1".equals(runner)) {
                container.setCommand(List.of("python","-m","evals.acceptance_worker"));
                container.getEnv().add(new EnvVarBuilder().withName("KEEL_EVAL_CAPABILITY").withNewValueFrom()
                        .withNewSecretKeyRef().withName(EvaluationWorkerBridge.secretName(row.id)).withKey("capability").endSecretKeyRef().endValueFrom().build());
            }
            var spec=new PodSpecBuilder().withRestartPolicy("Never").withAutomountServiceAccountToken(false)
                    .withEnableServiceLinks(false).withContainers(container).withVolumes(pod.getSpec().getVolumes())
                    .withHostAliases(pod.getSpec().getHostAliases()).withImagePullSecrets(pod.getSpec().getImagePullSecrets()).build();
            var created=new JobBuilder().withNewMetadata().withName(name).withNamespace(row.namespace).endMetadata()
                    .withNewSpec().withBackoffLimit(0).withActiveDeadlineSeconds(3600L).withTtlSecondsAfterFinished(86400)
                    .withNewTemplate().withNewMetadata().addToLabels("keel.io/eval-run",row.id).endMetadata()
                    .withSpec(spec).endTemplate().endSpec().build();
            try {cluster.batch().v1().jobs().inNamespace(row.namespace).resource(created).create();}
            catch(KubernetesClientException e) {if(e.getCode()!=409)throw e;}
            if("acceptance-v1".equals(runner)) {
                var launched=cluster.batch().v1().jobs().inNamespace(row.namespace).withName(name).get();
                var auth=cluster.secrets().inNamespace(row.namespace).withName(EvaluationWorkerBridge.secretName(row.id)).get();
                if(launched!=null && auth!=null) {
                    auth.getMetadata().setManagedFields(null);
                    auth.getMetadata().setOwnerReferences(List.of(new OwnerReferenceBuilder().withApiVersion("batch/v1").withKind("Job")
                            .withName(name).withUid(launched.getMetadata().getUid()).withBlockOwnerDeletion(false).build()));
                    cluster.secrets().inNamespace(row.namespace).resource(auth).update();
                }
            }
            jdbc.update("UPDATE eval_run SET state='RUNNING',updated_at=now() WHERE run_id=? AND state='QUEUED'",row.id);
            return;
        }
        if("QUEUED".equals(row.state)) jdbc.update("UPDATE eval_run SET state='RUNNING',updated_at=now() WHERE run_id=? AND state='QUEUED'",row.id);
        var status=job.getStatus();
        if(status==null)return;
        boolean success=status.getSucceeded()!=null && status.getSucceeded()>0;
        boolean failed=status.getConditions()!=null && status.getConditions().stream().anyMatch(c->"Failed".equals(c.getType())&&"True".equals(c.getStatus()));
        if(!success && !failed)return;
        var log=cluster.batch().v1().jobs().inNamespace(row.namespace).withName(name).getLog();
        var line=log.lines().filter(s->s.startsWith("KEEL_EVAL_RESULT ")).reduce((a,b)->b).orElse(null);
        if(!success || line==null) {
            var error=log.lines().filter(s->s.startsWith("KEEL_EVAL_ERROR ")).reduce((a,b)->b).orElse("评测执行失败，请检查镜像 eval 依赖、评分器和数据集");
            fail(row.id,error);return;
        }
        var result=decode(line.substring("KEEL_EVAL_RESULT ".length()));
        if(!row.agent.equals(result.get("agent")) || !row.id.equals(result.get("candidateVersion")) || !row.dataset.equals(result.get("dataset"))) {
            fail(row.id,"评测结果身份不匹配");return;
        }
        var finalRunner=jdbc.queryForObject("SELECT runner FROM eval_run WHERE run_id=?",String.class,row.id);
        if("acceptance-v1".equals(finalRunner)) {
            var persisted=jdbc.queryForObject("SELECT progress_result::text FROM eval_run WHERE run_id=?",String.class,row.id);
            if(persisted==null || !parse(persisted).equals(parse(line.substring("KEEL_EVAL_RESULT ".length())))) {fail(row.id,"评测结果与持久化证据不一致");return;}
        }
        jdbc.update("UPDATE eval_run SET state='DONE',result=?::jsonb,updated_at=now() WHERE run_id=? AND state IN ('QUEUED','RUNNING')",
                line.substring("KEEL_EVAL_RESULT ".length()),row.id);
    }

    String baseline(String agent) {
        var rows = jdbc.query("""
                WITH current_release AS (
                    SELECT * FROM release_record WHERE agent_name=? AND env='prod'
                    ORDER BY created_at DESC,id DESC LIMIT 1
                )
                SELECT r.kind,r.ci_run_url,r.image,e.run_id,e.agent_name,e.image,e.state,e.result::text
                FROM current_release r LEFT JOIN eval_run e ON e.run_id=r.ci_run_url
                """, (rs, n) -> {
            if (!"release".equals(rs.getString(1))) return null;
            if (rs.getString(4) == null && (rs.getString(2) == null || !rs.getString(2).startsWith("ev_")))
                return null; // Legacy releases have no platform evaluation to compare against.
            var result = rs.getString(8) == null ? null : parse(rs.getString(8));
            if (!agent.equals(rs.getString(5)) || rs.getString(3) == null
                    || !rs.getString(3).equals(rs.getString(6)) || !"DONE".equals(rs.getString(7))
                    || result == null || !result.path("complete").asBoolean() || !result.path("passed").asBoolean()
                    || !result.path("dimensions").isArray() || result.path("dimensions").isEmpty())
                throw new IllegalStateException("当前生产发布的评测基线证据无效");
            return result;
        }, agent);
        var values = json.createObjectNode();
        if (!rows.isEmpty() && rows.getFirst() != null)
            for (var d : rows.getFirst().path("dimensions")) {
                var tag = d.path("tag").asText("");
                var score = d.path("candidateScore");
                if (tag.isBlank() || values.has(tag) || !score.isNumber()
                        || !Double.isFinite(score.asDouble()) || score.asDouble() < 0 || score.asDouble() > 1)
                    throw new IllegalStateException("当前生产发布的评测维度无效");
                values.put(tag,score.asDouble());
            }
        return values.toString();
    }
    private void fail(String id,String error) {jdbc.update("UPDATE eval_run SET state='FAILED',error=?,updated_at=now() WHERE run_id=? AND state IN ('QUEUED','RUNNING')",error,id);}
    private JsonNode parse(String text) {try{return json.readTree(text);}catch(Exception e){throw new IllegalStateException("评测结果无法解析",e);}}
    @SuppressWarnings("unchecked") private Map<String,Object> decode(String text) {return text==null?null:json.convertValue(parse(text),Map.class);}
    static String jobName(String id) {return "keel-eval-"+id.replace('_','-');}
    static boolean ready(Pod p) {return p.getStatus()!=null && p.getStatus().getConditions()!=null && p.getStatus().getConditions().stream().anyMatch(c->"Ready".equals(c.getType())&&"True".equals(c.getStatus()));}
    private record Run(String id,String agent,String env,String namespace,String pod,String image,String endpoint,String dataset,String state,Instant created) {}
}
