package com.keel.llm;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
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
        this.models = Collections.unmodifiableMap(copy);
    }

    /** Vendor label for the console. Empty when the upstream host is not one of the known providers. */
    public static String provider(String upstream) {
        var host = host(upstream);
        if (host.contains("dashscope")) {
            return "DashScope";
        }
        if (host.contains("deepseek")) {
            return "DeepSeek";
        }
        return "";
    }

    private static String host(String upstream) {
        if (upstream == null || upstream.isBlank()) {
            return "";
        }
        try {
            var host = URI.create(upstream).getHost();
            return host == null ? upstream.toLowerCase(Locale.ROOT) : host.toLowerCase(Locale.ROOT);
        } catch (IllegalArgumentException e) {
            return upstream.toLowerCase(Locale.ROOT);
        }
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
