package com.keel.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;

import java.util.ArrayList;
import java.util.List;

/** Published alert rules shared by all gateway replicas. */
public final class AlertRules {
    private static final String KEY = "keel-llm:alert-rules:v1";
    private static final ObjectMapper JSON = new ObjectMapper();
    private final RedisCommands<String, String> redis;
    private final List<Gateway.AlertRule> local = new ArrayList<>();

    public AlertRules(String redisUri) {
        redis = redisUri == null || redisUri.isBlank() ? null : RedisClient.create(redisUri).connect().sync();
    }

    public Gateway.AlertRule add(String name, String metric, double threshold, String window, String groupBy) {
        if (redis == null) {
            synchronized (local) {
                var rule = new Gateway.AlertRule("rule-" + (local.size() + 1), name, metric, threshold, window, groupBy);
                local.add(rule);
                return rule;
            }
        }
        long id = redis.eval("return redis.call('INCR',KEYS[1])", ScriptOutputType.INTEGER,
                new String[]{KEY + ":next"});
        var rule = new Gateway.AlertRule("rule-" + id, name, metric, threshold, window, groupBy);
        try {
            redis.rpush(KEY, JSON.writeValueAsString(rule));
        } catch (Exception error) {
            throw new IllegalStateException("告警规则保存失败", error);
        }
        return rule;
    }

    public List<Gateway.AlertRule> list() {
        if (redis == null) {
            synchronized (local) { return List.copyOf(local); }
        }
        try {
            var rules = new ArrayList<Gateway.AlertRule>();
            for (var raw : redis.lrange(KEY, 0, -1)) rules.add(JSON.readValue(raw, Gateway.AlertRule.class));
            return rules;
        } catch (Exception error) {
            throw new IllegalStateException("告警规则读取失败", error);
        }
    }
}
