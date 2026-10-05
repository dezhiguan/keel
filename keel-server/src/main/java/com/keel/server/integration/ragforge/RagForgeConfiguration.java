package com.keel.server.integration.ragforge;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RagForgeConfiguration {
    @Bean
    RagForgeInsightClient ragForgeInsightClient(@Value("${RAGFORGE_BASE_URL:}") String baseUrl,
                                                @Value("${RAGFORGE_METRICS_USER:metrics-reader}") String user,
                                                @Value("${RAGFORGE_METRICS_PASSWORD:}") String password) {
        return new RagForgeInsightClient(baseUrl, user, password);
    }
}
