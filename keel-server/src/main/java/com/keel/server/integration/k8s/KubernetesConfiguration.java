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
        // 不在集群里时，fabric8 仍会把地址设成 kubernetes.default.svc，测试和本机都会解析失败。
        if (System.getenv("KUBERNETES_SERVICE_HOST") == null) {
            return new KubernetesClientBuilder()
                    .withConfig(new ConfigBuilder().withMasterUrl("http://127.0.0.1:1").build())
                    .build();
        }
        return new KubernetesClientBuilder().build();
    }
}
