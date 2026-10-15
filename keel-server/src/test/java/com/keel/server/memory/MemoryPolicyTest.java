package com.keel.server.memory;

import com.keel.common.error.ErrorCode;
import com.keel.server.common.KeelException;
import com.keel.server.integration.audit.JdbcAuditStore;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MemoryPolicyTest {
    @Test void metaAgentAndEvalTrafficCannotWrite() {
        assertThatThrownBy(() -> MemoryPolicy.requireWritable("META", "biz-agent", null))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.MEMORY_WRITE_DENIED);
        assertThatThrownBy(() -> MemoryPolicy.requireWritable("BIZ", "meta-agent", ""))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.MEMORY_WRITE_DENIED);
        assertThatThrownBy(() -> MemoryPolicy.requireWritable("BIZ", "careermate", "eval-1"))
                .isInstanceOf(KeelException.class)
                .extracting(error -> ((KeelException) error).code())
                .isEqualTo(ErrorCode.MEMORY_WRITE_DENIED);
        MemoryPolicy.requireWritable("BIZ", "careermate", null);
    }

    @Test void auditKeepsCountAndDropsMemoryText() {
        var kept = JdbcAuditStore.kept(Map.of("count", 1, "category", "USER", "content", "用户原文"));
        assertThat(kept).containsEntry("count", 1).containsEntry("category", "USER");
        assertThat(kept).doesNotContainKey("content");
    }
}
