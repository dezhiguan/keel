package com.keel.llm;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RoutePolicyTest {
    @Test void twoFailuresOpenTheChannelAndTheNextPlanSkipsIt() {
        var policy = new RoutePolicy();
        var catalog = new ModelCatalog(Map.of(
                "qwen-plus", new ModelCatalog.Model("qwen-plus", new BigDecimal("0.0000008"), new BigDecimal("0.000002"), "http://qwen", "k"),
                "deepseek-v3", new ModelCatalog.Model("deepseek-v3", new BigDecimal("0.000001"), new BigDecimal("0.000002"), "http://deepseek", "k")));
        var key = new Gateway.VirtualKey("askdb-dev", "token", List.of("qwen-plus", "deepseek-v3"),
                List.of("deepseek-v3"), new BigDecimal("30"), true, false, 1);
        policy.observe("http://qwen", true);
        policy.observe("http://qwen", true);
        assertThat(policy.open("http://qwen")).isTrue();
        assertThat(policy.plan(key, "qwen-plus", false, catalog)).containsExactly("deepseek-v3");
    }
}
