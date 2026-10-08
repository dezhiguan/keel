package com.keel.server.devflow;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * In-memory stand-in so the console jobs page has a route.
 * The ledger, audit, and lineage checks belong to DF-2.
 */
@Service
public class DevflowService {
    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private static final List<String> STAGES = List.of(
            "SPEC", "H1", "EVAL", "H2", "H3", "BUILD", "REVIEW", "GATE", "H4", "RELEASE", "WATCH");
    private static final Set<String> BUILDING = Set.of("BUILD", "REVIEW", "GATE");
    private static final List<DevflowTypes.PreviewRow> PREVIEW = List.of(
            new DevflowTypes.PreviewRow("refund-explainer", "退款规则解释", "AUTO", "陈主管", null),
            new DevflowTypes.PreviewRow("cs-summary", "客服会话小结", "AUTO", "陈主管", null),
            new DevflowTypes.PreviewRow("cs-summary2", "客服对话总结", "AUTO", "陈主管", "bad:与第 2 行重复"),
            new DevflowTypes.PreviewRow("vip-callback", "VIP 回访话术", "COLLAB", "陈主管", null),
            new DevflowTypes.PreviewRow("complaint-route", "投诉分级", "SCAFFOLD", "—", "warn:缺负责人"));

    private final List<DevflowTypes.Job> jobs = new ArrayList<>();
    private final List<DevflowTypes.Batch> batches = new ArrayList<>();
    private DevflowTypes.Settings settings = DevflowTypes.Settings.defaults();

    public synchronized DevflowTypes.JobList list(String layer, String status, String stage, String batch, boolean mine, String actor) {
        List<DevflowTypes.Job> items = jobs.stream()
                .filter(job -> layer == null || layer.equals(job.layer()))
                .filter(job -> status == null || status.equals(job.status()))
                .filter(job -> stage == null || stage.equals(job.stage()))
                .filter(job -> batch == null || batch.equals(job.batchId()))
                .filter(job -> !mine || actor.equals(job.requester()))
                .toList();
        double spent = jobs.stream().mapToDouble(DevflowTypes.Job::spentCny).sum();
        var summary = new DevflowTypes.Summary(
                (int) jobs.stream().filter(job -> isActive(job.status())).count(),
                (int) jobs.stream().filter(job -> "QUEUED".equals(job.status())).count(),
                settings.dailyLimit(),
                (int) jobs.stream().filter(this::waiting).count(),
                (int) jobs.stream().filter(job -> "HUMAN".equals(job.status())).count(),
                0,
                0,
                Math.round(spent * 10) / 10.0);
        return new DevflowTypes.JobList(summary, items);
    }

    public synchronized DevflowTypes.Job job(String jobId) {
        return jobs.stream().filter(job -> job.jobId().equals(jobId)).findFirst()
                .orElseThrow(() -> new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message()));
    }

    public synchronized DevflowTypes.BatchList batches() {
        List<DevflowTypes.Batch> items = batches.stream()
                .map(batch -> new DevflowTypes.Batch(batch.batchId(), batch.title(), batch.requester(), batch.concurrency(),
                        batch.pilotJobId(), batch.pilotPassed(), batch.createdAt(),
                        jobs.stream().filter(job -> batch.batchId().equals(job.batchId())).toList()))
                .toList();
        return new DevflowTypes.BatchList(items);
    }

    public synchronized DevflowTypes.Settings settings() {
        return settings;
    }

    public synchronized DevflowTypes.Settings save(DevflowTypes.Settings next) {
        if (next.templates() == null || next.templates().isEmpty()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "至少保留一个模板");
        }
        double[] numbers = {next.budgetCny(), next.maxFixRounds(), next.holdoutPercent(), next.minSeed(),
                next.keyCapCny(), next.dailyLimit(), next.concurrency()};
        for (double number : numbers) {
            if (Double.isNaN(number) || number < 0) {
                throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "数值不能为负");
            }
        }
        settings = new DevflowTypes.Settings(next.budgetCny(), next.maxFixRounds(), next.holdoutPercent(), next.minSeed(),
                next.keyCapCny(), next.dailyLimit(), next.concurrency(), List.copyOf(next.templates()));
        return settings;
    }

    public List<DevflowTypes.PreviewRow> preview() {
        return PREVIEW;
    }

    public synchronized DevflowTypes.Batch create(String title, List<DevflowTypes.PreviewRow> rows, String actor) {
        List<DevflowTypes.PreviewRow> ready = rows == null ? List.of() : rows.stream().filter(row -> row.flag() == null || row.flag().isBlank()).toList();
        if (title == null || title.isBlank() || ready.isEmpty()) {
            throw new KeelException(ErrorCode.SERVER_INVALID_PARAM, "没有可生产的行");
        }
        String batchId = "B-" + String.format("%02d", batches.size() + 3);
        List<DevflowTypes.Job> created = new ArrayList<>();
        for (int i = 0; i < ready.size(); i++) {
            DevflowTypes.PreviewRow row = ready.get(i);
            DevflowTypes.Job job = DevflowTypes.Job.create(nextId(), row.title(), row.targetAgent(), row.mode(), row.owner(),
                    "客服中心", batchId, i == 0 ? "RUN" : "QUEUED", row.title(), settings.budgetCny(), settings.maxFixRounds(),
                    List.of(new DevflowTypes.Event(now(), actor, i == 0 ? "批次试产条目" : "批次排队")));
            jobs.add(job);
            created.add(job);
        }
        var batch = new DevflowTypes.Batch(batchId, title.trim(), actor, settings.concurrency(), created.get(0).jobId(),
                false, "刚刚", created);
        batches.add(0, batch);
        return batch;
    }

    public synchronized DevflowTypes.Job takeover(String jobId, String actor) {
        DevflowTypes.Job job = job(jobId);
        if (!canTakeover(job)) throw invalid("当前阶段不能人工接管");
        String stage = job.stage();
        if (index(stage) > index("GATE")) stage = "GATE";
        if ("REVIEW".equals(stage)) stage = "BUILD";
        return replace(job.withStatus("HUMAN", stage, false, actor, event(job, actor, "人工接管开发")));
    }

    public synchronized DevflowTypes.Job handback(String jobId, String actor) {
        DevflowTypes.Job job = job(jobId);
        if (!"HUMAN".equals(job.status())) throw invalid("只有人工开发中的任务可以交还");
        String summary = "SCAFFOLD".equals(job.mode()) ? "提交门禁" : "交还智能体";
        return replace(job.withStatus("RUN", "REVIEW", job.needReview(), job.humanDevUser(), event(job, actor, summary)));
    }

    public synchronized DevflowTypes.Job assist(String jobId, String instruction, String actor) {
        DevflowTypes.Job job = job(jobId);
        if (!"HUMAN".equals(job.status())) throw invalid("只有人工开发中的任务可以请智能体帮忙");
        String text = instruction == null ? "" : instruction.trim();
        if (text.isEmpty()) throw invalid("请写明要它做什么");
        List<DevflowTypes.Event> events = new ArrayList<>(job.events());
        events.add(new DevflowTypes.Event(now(), actor, "请 dev-agent 帮忙：" + text));
        events.add(new DevflowTypes.Event(now(), "dev-agent", "按指令完成，推送 1 次提交"));
        return replace(job.withEvents(events));
    }

    public synchronized DevflowTypes.Job cancel(String jobId, String actor) {
        DevflowTypes.Job job = job(jobId);
        boolean open = Set.of("RUN", "WAIT", "HUMAN", "QUEUED").contains(job.status());
        if (!open || "WATCH".equals(job.stage())) throw invalid("当前状态不能取消");
        return replace(job.withStatus("CANCEL", job.stage(), job.needReview(), job.humanDevUser(), event(job, actor, "取消任务")));
    }

    private DevflowTypes.Job replace(DevflowTypes.Job next) {
        for (int i = 0; i < jobs.size(); i++) {
            if (jobs.get(i).jobId().equals(next.jobId())) {
                jobs.set(i, next);
                return next;
            }
        }
        throw new KeelException(ErrorCode.SERVER_NOT_FOUND, ErrorCode.SERVER_NOT_FOUND.message());
    }

    private List<DevflowTypes.Event> event(DevflowTypes.Job job, String actor, String summary) {
        List<DevflowTypes.Event> events = new ArrayList<>(job.events());
        events.add(new DevflowTypes.Event(now(), actor, summary));
        return events;
    }

    private String nextId() {
        int max = jobs.stream().mapToInt(job -> Integer.parseInt(job.jobId().substring(3))).max().orElse(0);
        return "DF-" + String.format("%04d", max + 1);
    }

    private static boolean canTakeover(DevflowTypes.Job job) {
        boolean building = "RUN".equals(job.status()) && BUILDING.contains(job.stage());
        return building || "FAIL".equals(job.status()) || (job.needReview() && "WAIT".equals(job.status()));
    }

    private boolean waiting(DevflowTypes.Job job) {
        return "WAIT".equals(job.status()) && (job.needReview() || Set.of("H1", "H2", "H4").contains(job.stage()));
    }

    private static boolean isActive(String status) {
        return "RUN".equals(status) || "WAIT".equals(status) || "HUMAN".equals(status);
    }

    private static int index(String stage) {
        int found = STAGES.indexOf(stage);
        return found < 0 ? 0 : found;
    }

    private static KeelException invalid(String message) {
        return new KeelException(ErrorCode.SERVER_INVALID_PARAM, message);
    }

    private static String now() {
        return CLOCK.format(ZonedDateTime.now(SHANGHAI));
    }
}
