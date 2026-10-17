package com.keel.server.memory;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MemoryLayersTest {
    @Test void metaCannotWriteAndDevCountsOnlyRealRows() {
        var meta = MemoryLayers.describe("META", "dev", "meta-agent", "chat", 9, 9, 9);
        assertThat(meta.session()).isEqualTo("不需要");
        assertThat(meta.user()).isEqualTo("不需要");
        assertThat(meta.task()).isEqualTo("只读生产履历");
        assertThat(meta.experience()).isEqualTo("不允许自写");

        var dev = MemoryLayers.describe(null, "dev", "qa-policy-advisor-1017", "chat", 0, 3, 0);
        assertThat(dev.session()).isEqualTo("不需要");
        assertThat(dev.user()).isEqualTo("不需要");
        assertThat(dev.task()).isEqualTo("按研发任务 · 3 条");
        assertThat(dev.experience()).isEqualTo("候选 0 条待人审");
    }

    @Test void businessChatUsesTheRealSessionTtlAndDoesNotInventRetention() {
        var chat = MemoryLayers.describe("BIZ", "biz", "careermate", "chat", 12, 0, 2);
        assertThat(chat.session()).isEqualTo("开启 · 7 天");
        assertThat(chat.user()).isEqualTo("开启");
        assertThat(chat.task()).isEqualTo("—");
        assertThat(chat.experience()).isEqualTo("候选 2 条待人审");

        var task = MemoryLayers.describe("BIZ", "biz", "ops-copilot", "task", 0, 0, 0);
        assertThat(task.session()).isEqualTo("关闭");
        assertThat(task.task()).isEqualTo("开启");

        var named = MemoryLayers.describe(null, null, "meta-agent", "chat", 1, 1, 1);
        assertThat(named.experience()).isEqualTo("不允许自写");
    }
}
