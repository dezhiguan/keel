package com.keel.server.discovery;

import com.keel.server.integration.langfuse.LangfuseClient;
import com.keel.server.integration.litellm.LiteLlmClient;
import io.fabric8.kubernetes.client.KubernetesClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class DiscoveryConfiguration {
    @Bean
    FindingBook findingBook(JdbcTemplate jdbc) {
        return new FindingBook(jdbc);
    }

    @Bean
    TrafficProbe trafficProbe(LangfuseClient langfuse, LiteLlmClient gateway) {
        return new TrafficProbe(langfuse, gateway);
    }

    @Bean
    K8sAgentWatcher k8sAgentWatcher(KubernetesClient client, JdbcTemplate jdbc) {
        var watcher = new K8sAgentWatcher(jdbc, null);
        watcher.start(client);
        return watcher;
    }

    @Bean
    ReconcileJob reconcileJob(FindingBook findings, TrafficProbe traffic, K8sAgentWatcher watcher, JdbcTemplate jdbc) {
        return new ReconcileJob(findings, traffic, watcher, jdbc);
    }

    @Bean
    InstanceBook instanceBook(JdbcTemplate jdbc) {
        return new InstanceBook(jdbc, null);
    }
}
