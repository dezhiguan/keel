package com.keel.server.integration.langfuse;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class LangfuseConfiguration {
    @Bean
    LangfuseClient langfuseClient(@Value("${LANGFUSE_HOST:}") String host,
                                  @Value("${LANGFUSE_PUBLIC_KEY:}") String publicKey,
                                  @Value("${LANGFUSE_SECRET_KEY:}") String secretKey) {
        return new LangfuseClient(host, publicKey, secretKey);
    }
}
