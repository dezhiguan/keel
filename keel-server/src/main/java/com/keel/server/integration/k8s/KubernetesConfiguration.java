package com.keel.server.integration.k8s;

import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class KubernetesConfiguration {
    @Bean
    KubernetesClient kubernetesClient() {
        try {
            return new KubernetesClientBuilder().build();
        } catch (Exception e) {
            return new KubernetesClientBuilder()
                    .withConfig(new ConfigBuilder().withMasterUrl("http://127.0.0.1:1").build())
                    .build();
        }
    }
}
