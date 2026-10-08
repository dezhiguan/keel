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
        var board = new DevflowService().list(null, null, null, null, false, "官德志");
        assertThat(board.items()).isEmpty();
        assertThat(board.summary().active()).isZero();
        assertThat(board.summary().dailyLimit()).isEqualTo(3);
        assertThat(board.summary().spentCny()).isZero();
    }

    @Test
    void submitLocksResearchWorkToCollaborationAndRejectsTheMetaAgent() {
        var service = new DevflowService();
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
                .isEqualTo(ErrorCode.SERVER_INVALID_PARAM);
    }

    @Test
    void missingJobIsNotFoundAndWatchCannotBeCancelled() {
        var service = new DevflowService();
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
        assertThatThrownBy(() -> service.save(new DevflowTypes.Settings(80, 3, 30, 30, 50, 3, 3, List.of())))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.SERVER_INVALID_PARAM);
    }
}
