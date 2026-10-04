package com.keel.server.integration.prometheus;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class PrometheusConfiguration {
    @Bean
    PrometheusClient prometheusClient(@Value("${PROMETHEUS_URL:}") String url) {
        return new PrometheusClient(url);
    }
}
