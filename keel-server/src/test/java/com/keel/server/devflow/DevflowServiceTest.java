package com.keel.server.devflow;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DevflowServiceTest {
    @Test
    void listsAnEmptyBoardInsteadOfMissingTheRoute() {
        var board = service().list(null, null, null, null, false, "官德志");
        assertThat(board.items()).isEmpty();
        assertThat(board.summary().active()).isZero();
        assertThat(board.summary().dailyLimit()).isEqualTo(3);
        assertThat(board.summary().spentCny()).isZero();
        assertThat(service().cost()).isNull();
    }

    @Test
    void submitLocksResearchWorkToCollaborationAndRejectsTheMetaAgent() {
        var service = service();
        var draft = new DevflowTypes.Draft("升级模板", "weekly-report", "DEV", "CREATE", "AUTO", "能生成 chat-rag",
                "graph-agent", 20.0, 0, List.of("rag.search"), List.of(), "研发效能组");
        var job = service.submit(draft, "官德志");
        assertThat(job.mode()).isEqualTo("COLLAB");
        assertThat(job.producerAgent()).isEqualTo("meta-agent");
        assertThat(job.jobId()).isEqualTo("DF-0001");
        assertThatThrownBy(() -> service.submit(new DevflowTypes.Draft("改自己", "meta-agent", "DEV", "CHANGE", "COLLAB",
                "不行", "graph-agent", 20.0, 0, List.of(), List.of(), "研发效能组"), "官德志"))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.DEVFLOW_LINEAGE_FORBIDDEN);
        assertThatThrownBy(() -> service.submit(new DevflowTypes.Draft("改自己", "meta-agent", "DEV", "CREATE", "COLLAB",
                "不行", "graph-agent", 20.0, 0, List.of(), List.of(), "研发效能组"), "meta-agent", "meta-agent"))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.DEVFLOW_LINEAGE_FORBIDDEN);
    }

    @Test
    void missingJobIsNotFoundAndWatchCannotBeCancelled() {
        var ledger = new MemoryDevflowLedger();
        var service = new DevflowService(ledger, new RecordingAudit());
        assertThatThrownBy(() -> service.job("DF-0019"))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.SERVER_NOT_FOUND);

        var created = service.create("客服", List.of(
                new DevflowTypes.PreviewRow("refund-explainer", "退款规则解释", "AUTO", "陈主管", null),
                new DevflowTypes.PreviewRow("cs-summary2", "重复", "AUTO", "陈主管", "bad:重复")), "官德志");
        assertThat(created.jobs()).hasSize(1);
        assertThat(created.jobs().get(0).status()).isEqualTo("RUN");
        assertThat(service.list(null, null, null, null, false, "官德志").summary().active()).isEqualTo(1);
        assertThatThrownBy(() -> service.takeover(created.pilotJobId(), "官德志"))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.SERVER_INVALID_PARAM);
        assertThat(service.cancel(created.pilotJobId(), "官德志").status()).isEqualTo("CANCEL");
        assertThatThrownBy(() -> service.save(new DevflowTypes.Settings(80, 3, 30, 30, 50, 3, 3, List.of()), "官德志"))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.SERVER_INVALID_PARAM);

        var watching = created.jobs().get(0).withStatus("RUN", "WATCH", false, null, created.jobs().get(0).events());
        ledger.insert(watching);
        assertThatThrownBy(() -> service.cancel(watching.jobId(), "官德志"))
                .isInstanceOf(KeelException.class);
    }

    @Test
    void aStageReportAdvancesAndATakeoverIsAudited() {
        var audit = new RecordingAudit();
        var ledger = new MemoryDevflowLedger();
        var service = new DevflowService(ledger, audit);
        var job = service.submit(new DevflowTypes.Draft("升级模板", "weekly-report", "DEV", "CREATE", "AUTO", "能生成",
                "graph-agent", null, 0, List.of(), List.of(), "研发效能组"), "官德志");
        var reported = service.report(job.jobId(), "SPEC", new DevflowTypes.StageReport("OK", "需求写完", null, 1.0, null), "meta-agent");
        assertThat(reported.stage()).isEqualTo("H1");
        assertThat(reported.status()).isEqualTo("WAIT");
        assertThat(reported.spentCny()).isEqualTo(1.0);
        assertThat(service.review(job.jobId())).containsEntry("gate", "H1");
        assertThatThrownBy(() -> service.report(job.jobId(), "H1",
                new DevflowTypes.StageReport("OK", "再花一笔", null, 200.0, null), "meta-agent"))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.DEVFLOW_BUDGET_EXCEEDED);

        var building = reported.withStatus("RUN", "BUILD", false, null, reported.events());
        ledger.update(building);
        var auditBefore = audit.payloads.size();
        assertThat(service.takeover(building.jobId(), "官德志").status()).isEqualTo("HUMAN");
        assertThat(audit.payloads.get(auditBefore)).containsEntry("kind", "devflow.takeover");
        assertThat(service.cost()).isNotNull();
    }

    @Test
    void fixRoundsStopTheJob() {
        var ledger = new MemoryDevflowLedger();
        var service = new DevflowService(ledger, new RecordingAudit());
        var job = service.submit(new DevflowTypes.Draft("升级模板", "weekly-report", "BIZ", "CREATE", "AUTO", "能生成",
                "tool-agent", null, 0, List.of(), List.of(), "研发效能组"), "官德志");
        ledger.update(job.withProgress("RUN", "GATE", 0, 3, job.events()));
        assertThatThrownBy(() -> service.report(job.jobId(), "GATE",
                new DevflowTypes.StageReport("FAILED", "还是不行", null, 0.0, null), "dev-lead"))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.DEVFLOW_FIX_ROUNDS_EXHAUSTED);
        assertThat(service.job(job.jobId()).status()).isEqualTo("FAIL");
    }

    private static DevflowService service() {
        return new DevflowService(new MemoryDevflowLedger(), new RecordingAudit());
    }
}
