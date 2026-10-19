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

    @Test void tripOpensForSixtySecondsThenWaitsForAProbe() {
        long[] now = {1_000_000L};
        var policy = new RoutePolicy(null, () -> now[0]);
        var tripped = policy.trip("http://qwen");
        assertThat(tripped.state()).isEqualTo("open");
        assertThat(policy.open("http://qwen")).isTrue();
        assertThat(policy.view("http://other").state()).isEqualTo("closed");
        now[0] += 60_001;
        assertThat(policy.view("http://qwen").state()).isEqualTo("half_open");
        assertThat(policy.open("http://qwen")).isFalse();
        policy.close("http://qwen");
        assertThat(policy.view("http://qwen").state()).isEqualTo("closed");
    }

    @Test void latencyObjectiveUsesMeasuredChannelLatency() {
        var policy = new RoutePolicy();
        var catalog = new ModelCatalog(Map.of(
                "primary", new ModelCatalog.Model("primary", new BigDecimal("0.000001"), new BigDecimal("0.000001"), "http://primary", "k"),
                "slow", new ModelCatalog.Model("slow", new BigDecimal("0.000001"), new BigDecimal("0.000001"), "http://slow", "k"),
                "fast", new ModelCatalog.Model("fast", new BigDecimal("0.000001"), new BigDecimal("0.000001"), "http://fast", "k")));
        var key = new Gateway.VirtualKey("askdb-dev", "token", List.of("primary", "slow", "fast"),
                List.of("slow", "fast"), new BigDecimal("30"), true, false, 1);
        policy.observe("http://slow", false, 500);
        policy.observe("http://fast", false, 20);
        policy.publish("latency");
        assertThat(policy.plan(key, "primary", false, catalog)).containsExactly("primary", "fast");
    }
}
