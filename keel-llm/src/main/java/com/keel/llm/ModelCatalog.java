package com.keel.llm;

import java.math.BigDecimal;
import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Prices are CNY per token. A missing or non-positive price refuses startup. */
public final class ModelCatalog {
    public record Model(String name, BigDecimal inputCnyPerToken, BigDecimal outputCnyPerToken, String upstream, String upstreamKey,
                        int maxInputTokens, int maxOutputTokens, String priceVersion, BigDecimal cacheReadCnyPerToken,
                        boolean embedding) {
        public Model(String name, BigDecimal inputCnyPerToken, BigDecimal outputCnyPerToken, String upstream, String upstreamKey) {
            this(name, inputCnyPerToken, outputCnyPerToken, upstream, upstreamKey, 1_000_000, 262_144, "", null, false);
        }

        public Model(String name, BigDecimal inputCnyPerToken, BigDecimal outputCnyPerToken, String upstream, String upstreamKey,
                     int maxInputTokens, int maxOutputTokens) {
            this(name, inputCnyPerToken, outputCnyPerToken, upstream, upstreamKey, maxInputTokens, maxOutputTokens, "", null, false);
        }

        public boolean priceConfigured() {
            if (inputCnyPerToken.signum() <= 0 || maxInputTokens <= 0) {
                return false;
            }
            if (cacheReadCnyPerToken != null && cacheReadCnyPerToken.signum() <= 0) {
                return false;
            }
            if (embedding) {
                return outputCnyPerToken.signum() >= 0;
            }
            return outputCnyPerToken.signum() > 0 && maxOutputTokens > 0;
        }

        public String version() {
            if (priceVersion != null && !priceVersion.isBlank()) {
                return priceVersion;
            }
            var cache = cacheReadCnyPerToken == null ? "" : cacheReadCnyPerToken.toPlainString();
            return inputCnyPerToken.toPlainString() + "/" + outputCnyPerToken.toPlainString() + "/" + cache;
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

    public static String host(String upstream) {
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
