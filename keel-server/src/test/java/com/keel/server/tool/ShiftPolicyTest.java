package com.keel.server.tool;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ShiftPolicyTest {
    @Test void onlyTheThreeDutyCategoriesHaveText() throws Exception {
        var json = JsonMapper.builder().build();
        assertThat(ShiftPolicy.known("故障")).isTrue();
        assertThat(ShiftPolicy.known("其它")).isFalse();
        var answer = ShiftPolicy.answer(json.readTree("{\"category\":\"故障\"}"));
        assertThat(answer.path("text").asText()).contains("升级值班长");
        assertThat(ShiftPolicy.answer(json.readTree("{}")).path("text").asText()).isEmpty();
    }
}
