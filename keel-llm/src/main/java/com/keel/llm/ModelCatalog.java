package com.keel.llm;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Prices are CNY per token. A missing or non-positive price refuses startup. */
public final class ModelCatalog {
    public record Model(String name, BigDecimal inputCnyPerToken, BigDecimal outputCnyPerToken, String upstream, String upstreamKey) {
        public boolean priceConfigured() {
            return inputCnyPerToken.signum() > 0 && outputCnyPerToken.signum() > 0;
        }
    }

    private final Map<String, Model> models;

    public ModelCatalog(Map<String, Model> models) {
        var copy = new LinkedHashMap<String, Model>();
        models.forEach((name, model) -> {
            if (!model.priceConfigured()) {
                throw new IllegalStateException("缺单价: " + name);
            }
            copy.put(name, model);
        });
        if (copy.isEmpty()) {
            throw new IllegalStateException("缺单价");
        }
        this.models = Map.copyOf(copy);
    }

    public Model require(String name) {
        var model = models.get(name);
        if (model == null) {
            throw new IllegalArgumentException("未知模型: " + name);
        }
        return model;
    }

    public Map<String, Model> models() {
        return models;
    }
}
