package com.keel.server.insight;

import com.keel.server.integration.litellm.LiteLlmClient;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class CostService {
    private final LiteLlmClient gateway;

    public CostService(LiteLlmClient gateway) {
        this.gateway = gateway;
    }

    public Result cost() {
        var rows = gateway.spendAll();
        var byAgent = new LinkedHashMap<String, Double>();
        double total = 0;
        var byModel = new LinkedHashMap<String, Double>();
        for (var row : rows) {
            total += row.costCny();
            byAgent.merge(agentOf(row.alias()), row.costCny(), Double::sum);
            byModel.merge(row.model(), row.costCny(), Double::sum);
        }
        return new Result(total, rows.size(), byAgent, byModel, gateway.models());
    }

    static String agentOf(String alias) {
        for (var suffix : List.of("-prod", "-staging", "-dev")) {
            if (alias.endsWith(suffix)) {
                return alias.substring(0, alias.length() - suffix.length());
            }
        }
        return alias;
    }

    public record Result(double totalCny, int calls, Map<String, Double> byAgent, Map<String, Double> byModel, List<LiteLlmClient.Model> models) {}
}
