package com.keel.starter.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.keel.starter.manifest.AgentRegistry;
import com.keel.starter.manifest.ManifestLoader;
import com.keel.starter.web.FeedbackController;
import com.keel.starter.web.HealthController;
import com.keel.starter.web.InvokeController;
import com.keel.starter.web.KeelExceptionHandler;
import com.keel.starter.web.KeelRouteRegistrar;
import com.keel.starter.web.KeelSessions;
import com.keel.starter.web.ManifestController;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.web.servlet.DispatcherServlet;

@AutoConfiguration
@Conditional(ManifestPresentCondition.class)
@ConditionalOnClass(DispatcherServlet.class)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@EnableConfigurationProperties(KeelProperties.class)
public class KeelAutoConfiguration {
    @Bean
    ManifestLoader manifestLoader(org.springframework.core.io.ResourceLoader loader) {
        return new ManifestLoader(loader);
    }

    @Bean
    AgentRegistry agentRegistry() {
        return new AgentRegistry();
    }

    @Bean
    KeelSessions keelSessions() {
        return new KeelSessions();
    }

    @Bean
    InvokeController invokeController(AgentRegistry registry, KeelSessions sessions, ObjectMapper mapper) {
        return new InvokeController(registry, sessions, mapper);
    }

    @Bean
    HealthController healthController(AgentRegistry registry) {
        return new HealthController(registry);
    }

    @Bean
    ManifestController manifestController(AgentRegistry registry) {
        return new ManifestController(registry);
    }

    @Bean
    FeedbackController feedbackController(AgentRegistry registry, ObjectMapper mapper) {
        return new FeedbackController(registry, mapper);
    }

    @Bean
    KeelExceptionHandler keelExceptionHandler() {
        return new KeelExceptionHandler();
    }

    @Bean
    KeelRouteRegistrar keelRouteRegistrar(AgentRegistry registry, ManifestLoader loader,
                                           InvokeController invoke, HealthController health,
                                           ManifestController manifest, FeedbackController feedback,
                                           ApplicationContext context) {
        return new KeelRouteRegistrar(registry, loader, invoke, health, manifest, feedback, context);
    }
}
