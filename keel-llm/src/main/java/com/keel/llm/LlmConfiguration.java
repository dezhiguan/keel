package com.keel.llm;

import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class LlmConfiguration {
    @Bean
    @ConfigurationProperties(prefix = "keel.llm")
    LlmProperties llmProperties() {
        return new LlmProperties();
    }

    @Bean
    ModelCatalog modelCatalog(LlmProperties properties) {
        var models = new LinkedHashMap<String, ModelCatalog.Model>();
        properties.getModels().forEach((name, model) -> models.put(name, new ModelCatalog.Model(
                name,
                decimal(model.getInputCnyPerToken()),
                decimal(model.getOutputCnyPerToken()),
                model.getUpstream(),
                model.getUpstreamKey())));
        return new ModelCatalog(models);
    }

    @Bean
    BudgetCounter budgetCounter(LlmProperties properties) {
        return new BudgetCounter(properties.getRedis());
    }

    @Bean
    Gateway gateway(ModelCatalog catalog, BudgetCounter budget, LlmProperties properties) {
        return new Gateway(catalog, budget, new Upstream(), new KeyDirectory(properties.getRedis()));
    }

    @Bean
    ApplicationRunner refuseStartupWithoutPrices(ModelCatalog catalog) {
        return args -> catalog.models().values().forEach(model -> {
            if (!model.priceConfigured()) {
                throw new IllegalStateException("缺单价: " + model.name());
            }
        });
    }

    private static BigDecimal decimal(String raw) {
        if (raw == null || raw.isBlank()) {
            return BigDecimal.ZERO;
        }
        return new BigDecimal(raw);
    }

    public static final class LlmProperties {
        private String adminKey = "";
        private String redis = "";
        private Map<String, ModelProperties> models = new LinkedHashMap<>();

        public String getAdminKey() { return adminKey; }
        public void setAdminKey(String adminKey) { this.adminKey = adminKey; }
        public String getRedis() { return redis; }
        public void setRedis(String redis) { this.redis = redis; }
        public Map<String, ModelProperties> getModels() { return models; }
        public void setModels(Map<String, ModelProperties> models) { this.models = models; }
    }

    public static final class ModelProperties {
        private String inputCnyPerToken = "";
        private String outputCnyPerToken = "";
        private String upstream = "";
        private String upstreamKey = "";

        public String getInputCnyPerToken() { return inputCnyPerToken; }
        public void setInputCnyPerToken(String inputCnyPerToken) { this.inputCnyPerToken = inputCnyPerToken; }
        public String getOutputCnyPerToken() { return outputCnyPerToken; }
        public void setOutputCnyPerToken(String outputCnyPerToken) { this.outputCnyPerToken = outputCnyPerToken; }
        public String getUpstream() { return upstream; }
        public void setUpstream(String upstream) { this.upstream = upstream; }
        public String getUpstreamKey() { return upstreamKey; }
        public void setUpstreamKey(String upstreamKey) { this.upstreamKey = upstreamKey; }
    }
}
