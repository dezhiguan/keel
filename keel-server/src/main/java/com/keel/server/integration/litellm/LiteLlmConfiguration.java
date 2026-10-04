package com.keel.server.integration.litellm;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LiteLlmConfiguration {
    @Bean
    LiteLlmClient liteLlmClient(@Value("${KEEL_LLM_BASE_URL:}") String baseUrl,
                                @Value("${KEEL_LLM_ADMIN_KEY:}") String masterKey) {
        return new LiteLlmClient(baseUrl, masterKey);
    }
}
