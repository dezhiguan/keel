package com.keel.server.devflow;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.audit.AuditStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Research-job ledger. State rules are in {@link DevflowRules}; this class stores them and writes the audit. */
@Service
public class DevflowService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final List<DevflowTypes.PreviewRow> PREVIEW = List.of(
            new DevflowTypes.PreviewRow("refund-explainer", "退款规则解释", "AUTO", "陈主管", null),
            new DevflowTypes.PreviewRow("cs-summary", "客服会话小结", "AUTO", "陈主管", null),
            new DevflowTypes.PreviewRow("cs-summary2", "客服对话总结", "AUTO", "陈主管", "bad:与第 2 行重复"),
            new DevflowTypes.PreviewRow("vip-callback", "VIP 回访话术", "COLLAB", "陈主管", null),
            new DevflowTypes.PreviewRow("complaint-route", "投诉分级", "SCAFFOLD", "—", "warn:缺负责人"));

    private final DevflowLedger ledger;
    private final AuditStore audit;
    private final TransactionTemplate transactions;

    @Autowired
    public DevflowService(DevflowLedger ledger, AuditStore audit, PlatformTransactionManager transactions) {
        this(ledger, audit, new TransactionTemplate(transactions));
    }

    DevflowService(DevflowLedger ledger, AuditStore audit) {
        this(ledger, audit, (TransactionTemplate) null);
    }

    private DevflowService(DevflowLedger ledger, AuditStore audit, TransactionTemplate transactions) {
        this.ledger = ledger;
        this.audit = audit;
        this.transactions = transactions;
    }

    public synchronized DevflowTypes.JobList list(String layer, String status, String stage, String batch, boolean mine, String actor) {
        var jobs = ledger.jobs();
        var settings = ledger.settings();
        List<DevflowTypes.Job> items = jobs.stream()
                .filter(job -> layer == null || layer.isBlank() || layer.equals(job.layer()))
                .filter(job -> status == null || status.isBlank() || status.equals(job.status()))
                .filter(job -> stage == null || stage.isBlank() || stage.equals(job.stage()))
                .filter(job -> batch == null || batch.isBlank() || batch.equals(job.batchId()))
                .filter(job -> !mine || actor.equals(job.requester()))
                .toList();
        double spent = jobs.stream().mapToDouble(DevflowTypes.Job::spentCny).sum();
        var summary = new DevflowTypes.Summary(
                (int) jobs.stream().filter(job -> isActive(job.status())).count(),
                (int) jobs.stream().filter(job -> "QUEUED".equals(job.status())).count(),
                settings.dailyLimit(),
                (int) jobs.stream().filter(this::waiting).count(),
                (int) jobs.stream().filter(job -> "HUMAN".equals(job.status())).count(),
                0, 0, Math.round(spent * 10) / 10.0);
        return new DevflowTypes.JobList(summary, items);
    }

    public synchronized DevflowTypes.Job submit(DevflowTypes.Draft draft, String actor) {
        return submit(draft, actor, null);
    }

    public synchronized DevflowTypes.Job submit(DevflowTypes.Draft draft, String actor, String serviceAgent) {
        if (draft == null || blank(draft.title()) || blank(draft.goal()) || blank(draft.targetAgent())) {
            throw invalid("标题、目标和智能体 ID 必填");
        }
        if (!draft.targetAgent().matches("^[a-z][a-z0-9-]{1,38}[a-z0-9]$")) {
            throw invalid("智能体 ID 不合规");
        }
        String layer = draft.layer() == null || draft.layer().isBlank() ? "BIZ" : draft.layer();
        if (!Set.of("DEV", "BIZ").contains(layer)) {
            throw invalid("分类只能是业务或研发");
        }
        String kind = draft.kind() == null || draft.kind().isBlank() ? "CREATE" : draft.kind();
        DevflowRules.rejectLineage(layer, kind, draft.targetAgent(), serviceAgent);
        if ("CREATE".equals(kind) && ledger.jobs().stream().anyMatch(job -> draft.targetAgent().equals(job.targetAgent()) && !"CANCEL".equals(job.status()))) {
            throw invalid("该 ID 已存在；要改已有智能体请选改造");
        }
        var settings = ledger.settings();
        if (draft.dailyBudgetCny() != null && draft.dailyBudgetCny() > settings.keyCapCny()) {
            throw invalid("日预算超过上限");
        }
        String mode = DevflowRules.mode(layer, draft.mode());
        List<DevflowTypes.ToolRef> tools = draft.tools() == null ? List.of() : draft.tools().stream()
                .map(name -> new DevflowTypes.ToolRef(name, "LOW", "—")).toList();
        var job = new DevflowTypes.Job(ledger.nextJobId(), draft.title().trim(), layer, kind, mode, "RUN", "SPEC",
                draft.targetAgent(), DevflowRules.producer(layer),
                blank(draft.template()) ? "tool-agent" : draft.template(), 0,
                "DEV".equals(layer) ? 120 : settings.budgetCny(), 0, settings.maxFixRounds(), null, false, null, null,
                actor, blank(draft.ownerOrg()) ? "研发效能组" : draft.ownerOrg(), draft.goal().trim(), tools,
                draft.knowledge() == null ? List.of() : List.copyOf(draft.knowledge()),
                new DevflowTypes.Seed(draft.seedCount() == null ? 0 : draft.seedCount(), 0, 0),
                List.of(new DevflowTypes.Event(now(), actor, "提交需求")));
        commit(() -> {
            ledger.insert(job);
            audit(job, actor, "devflow.stage", "提交需求");
        });
        return job;
    }

    public synchronized DevflowTypes.Job job(String jobId) {
        return ledger.jobs().stream().filter(job -> job.jobId().equals(jobId)).findFirst()
                .orElseThrow(() -> new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message()));
    }

    public synchronized DevflowTypes.Job report(String jobId, String stage, DevflowTypes.StageReport body, String actor) {
        var current = job(jobId);
        if (body == null || stage == null || !stage.equals(current.stage())) {
            throw invalid("阶段不一致");
        }
        if (!current.producerAgent().equals(actor)) {
            throw new KeelException(ErrorCode.DEVFLOW_LINEAGE_FORBIDDEN, ErrorCode.DEVFLOW_LINEAGE_FORBIDDEN.message());
        }
        var step = DevflowRules.advance(current, body.status(), body.costCny(), body.summary(), actor, now());
        commit(() -> {
            ledger.update(step.job());
            ledger.stage(jobId, stage, actor, step.job().fixRounds() + 1, body.status(), body.traceId(), body.costCny(), body.summary());
            if (body.artifact() != null) {
                ledger.artifact(jobId, body.artifact());
            }
            audit(step.job(), actor, "devflow.stage", body.summary());
        });
        if (step.exhausted()) {
            throw new KeelException(ErrorCode.DEVFLOW_FIX_ROUNDS_EXHAUSTED, ErrorCode.DEVFLOW_FIX_ROUNDS_EXHAUSTED.message());
        }
        return step.job();
    }

    public synchronized Map<String, Object> review(String jobId) {
        var job = job(jobId);
        var gate = DevflowRules.reviewGate(job);
        var body = new LinkedHashMap<String, Object>();
        body.put("jobId", job.jobId());
        body.put("gate", gate);
        if ("H1".equals(gate)) {
            body.put("specSheet", Map.of("title", job.title(), "goal", job.goal()));
        }
        if ("H2".equals(gate)) {
            body.put("humanCount", job.seed().human());
        }
        return body;
    }

    public synchronized DevflowTypes.SeedResult acceptSeeds(String jobId, List<String> acceptedCaseIds, String actor) {
        var current = job(jobId);
        if (!"H2".equals(DevflowRules.reviewGate(current))) {
            throw new KeelException(ErrorCode.RUN_NOT_RESUMABLE, ErrorCode.RUN_NOT_RESUMABLE.message());
        }
        int accepted = acceptedCaseIds == null ? 0 : (int) acceptedCaseIds.stream().filter(id -> id != null && !id.isBlank()).count();
        var seed = new DevflowTypes.Seed(current.seed().human(), accepted, 0);
        var next = current.withSeed(seed, event(current, actor, "采纳扩充用例 " + accepted + " 条"));
        commit(() -> {
            ledger.update(next);
            ledger.artifact(jobId, new DevflowTypes.Artifact("EVALSET", "accepted:" + accepted, null, "HUMAN"));
            audit(next, actor, "devflow.stage", "采纳扩充用例");
        });
        return new DevflowTypes.SeedResult(seed.human(), 0, accepted);
    }

    public synchronized DevflowTypes.BatchList batches() {
        var jobs = ledger.jobs();
        List<DevflowTypes.Batch> items = ledger.batches().stream()
                .map(batch -> new DevflowTypes.Batch(batch.batchId(), batch.title(), batch.requester(), batch.concurrency(),
                        batch.pilotJobId(), batch.pilotPassed(), batch.createdAt(),
                        jobs.stream().filter(job -> batch.batchId().equals(job.batchId())).toList()))
                .toList();
        return new DevflowTypes.BatchList(items);
    }

    public synchronized DevflowTypes.Settings settings() {
        return ledger.settings();
    }

    public synchronized DevflowTypes.Settings save(DevflowTypes.Settings next, String actor) {
        if (next == null || next.templates() == null || next.templates().isEmpty()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "至少保留一个模板");
        }
        double[] numbers = {next.budgetCny(), next.maxFixRounds(), next.holdoutPercent(), next.minSeed(),
                next.keyCapCny(), next.dailyLimit(), next.concurrency()};
        for (double number : numbers) {
            if (Double.isNaN(number) || number < 0) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "数值不能为负");
            }
        }
        var saved = new DevflowTypes.Settings(next.budgetCny(), next.maxFixRounds(), next.holdoutPercent(), next.minSeed(),
                next.keyCapCny(), next.dailyLimit(), next.concurrency(), List.copyOf(next.templates()));
        commit(() -> {
            ledger.saveSettings(saved, actor);
            audit.append("keel", "dev", "config.change", "mid", "allowed", "devflow.settings", null, actor, Map.of());
        });
        return saved;
    }

    public List<DevflowTypes.PreviewRow> preview() {
        return PREVIEW;
    }

    public synchronized DevflowTypes.Batch create(String title, List<DevflowTypes.PreviewRow> rows, String actor) {
        List<DevflowTypes.PreviewRow> ready = rows == null ? List.of() : rows.stream()
                .filter(row -> row.flag() == null || row.flag().isBlank()).toList();
        if (title == null || title.isBlank() || ready.isEmpty()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "没有可生产的行");
        }
        var settings = ledger.settings();
        String batchId = ledger.nextBatchId();
        List<DevflowTypes.Job> created = new ArrayList<>();
        for (int i = 0; i < ready.size(); i++) {
            DevflowTypes.PreviewRow row = ready.get(i);
            boolean pilot = i == 0;
            created.add(DevflowTypes.Job.create(ledger.nextJobId(), row.title(), row.targetAgent(), row.mode(), row.owner(),
                    "客服中心", batchId, pilot ? "RUN" : "QUEUED", row.title(), settings.budgetCny(), settings.maxFixRounds(),
                    List.of(new DevflowTypes.Event(now(), actor, pilot ? "批次试产条目" : "批次排队"))));
        }
        var batch = new DevflowTypes.Batch(batchId, title.trim(), actor, settings.concurrency(), created.get(0).jobId(),
                false, "刚刚", created);
        commit(() -> {
            for (var job : created) {
                ledger.insert(job);
            }
            ledger.insertBatch(batch);
        });
        return batch;
    }

    public synchronized DevflowTypes.Job takeover(String jobId, String actor) {
        var job = job(jobId);
        if (!DevflowRules.canTakeover(job)) {
            throw invalid("当前阶段不能人工接管");
        }
        String stage = job.stage();
        if (index(stage) > index("GATE")) {
            stage = "GATE";
        }
        if ("REVIEW".equals(stage)) {
            stage = "BUILD";
        }
        var next = job.withStatus("HUMAN", stage, false, actor, event(job, actor, "人工接管开发"));
        commit(() -> {
            ledger.update(next);
            audit(next, actor, "devflow.takeover", "人工接管开发");
        });
        return next;
    }

    public synchronized DevflowTypes.Job handback(String jobId, String actor) {
        var job = job(jobId);
        if (!"HUMAN".equals(job.status())) {
            throw invalid("只有人工开发中的任务可以交还");
        }
        String summary = "SCAFFOLD".equals(job.mode()) ? "提交门禁" : "交还智能体";
        var next = job.withStatus("RUN", "REVIEW", job.needReview(), job.humanDevUser(), event(job, actor, summary));
        commit(() -> {
            ledger.update(next);
            audit(next, actor, "devflow.takeover", summary);
        });
        return next;
    }

    public synchronized DevflowTypes.Job assist(String jobId, String instruction, String actor) {
        var job = job(jobId);
        if (!"HUMAN".equals(job.status())) {
            throw invalid("只有人工开发中的任务可以请智能体帮忙");
        }
        String text = instruction == null ? "" : instruction.trim();
        if (text.isEmpty()) {
            throw invalid("请写明要它做什么");
        }
        var events = new ArrayList<>(job.events());
        events.add(new DevflowTypes.Event(now(), actor, "请 dev-agent 帮忙：" + text));
        events.add(new DevflowTypes.Event(now(), "dev-agent", "按指令完成，推送 1 次提交"));
        var next = job.withEvents(events);
        commit(() -> ledger.update(next));
        return next;
    }

    public synchronized DevflowTypes.Job cancel(String jobId, String actor) {
        var job = job(jobId);
        if (!DevflowRules.canCancel(job)) {
            throw invalid("当前状态不能取消");
        }
        var next = job.withStatus("CANCEL", job.stage(), job.needReview(), job.humanDevUser(), event(job, actor, "取消任务"));
        commit(() -> {
            ledger.update(next);
            audit(next, actor, "devflow.stage", "取消任务");
        });
        return next;
    }

    public Map<String, Object> cost() {
        return DevflowRules.cost(ledger.jobs());
    }

    private void audit(DevflowTypes.Job job, String actor, String kind, String summary) {
        audit.append(job.producerAgent(), "dev", "config.change", "mid", "allowed", job.jobId(), null, actor,
                Map.of("kind", kind));
    }

    private void commit(Runnable action) {
        if (transactions == null) {
            action.run();
            return;
        }
        transactions.executeWithoutResult(status -> action.run());
    }

    private List<DevflowTypes.Event> event(DevflowTypes.Job job, String actor, String summary) {
        var events = new ArrayList<>(job.events());
        events.add(new DevflowTypes.Event(now(), actor, summary));
        return events;
    }

    private boolean waiting(DevflowTypes.Job job) {
        return "WAIT".equals(job.status()) && (job.needReview() || Set.of("H1", "H2", "H4").contains(job.stage()));
    }

    private static boolean isActive(String status) {
        return "RUN".equals(status) || "WAIT".equals(status) || "HUMAN".equals(status);
    }

    private static int index(String stage) {
        int found = List.of("SPEC", "H1", "EVAL", "H2", "H3", "BUILD", "REVIEW", "GATE", "H4", "RELEASE", "WATCH").indexOf(stage);
        return found < 0 ? 0 : found;
    }

    private static KeelException invalid(String message) {
        return new KeelException(ErrorCode.SERVER_INVALID_PARAM, message);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static String now() {
        return CLOCK.format(ZonedDateTime.now(SHANGHAI));
    }
}
