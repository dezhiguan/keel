package com.keel.llm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class LogicalRoutesTest {
    @Test void prodChatDefaultUsesThePublishedWeight() {
        assertThat(LogicalRoutes.resolve("chat-default", "prod", 0)).isEqualTo("qwen3.8-flash");
        assertThat(LogicalRoutes.resolve("chat-default", "prod", 79)).isEqualTo("qwen3.8-flash");
        assertThat(LogicalRoutes.resolve("chat-default", "prod", 80)).isEqualTo("deepseek-flash");
    }

    @Test void nonProdChatDefaultStaysOnQwen() {
        assertThat(LogicalRoutes.resolve("chat-default", "dev", 90)).isEqualTo("qwen3.8-flash");
    }

    @Test void longContextProdUsesDeepseek() {
        assertThat(LogicalRoutes.resolve("long-context", "prod", 0)).isEqualTo("deepseek-flash");
        assertThat(LogicalRoutes.match("chat-cheap", "prod").gray()).isEqualTo("新规则 10%");
    }
}
