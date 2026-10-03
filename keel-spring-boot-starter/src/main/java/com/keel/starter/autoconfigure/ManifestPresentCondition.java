package com.keel.starter.autoconfigure;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.type.AnnotatedTypeMetadata;

/** Auto-configuration stays off until a manifest is actually on the classpath. */
class ManifestPresentCondition implements Condition {
    static final String DEFAULT_LOCATION = "classpath:agent.yaml";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        var loader = context.getResourceLoader();
        if (loader.getResource(DEFAULT_LOCATION).exists()) return true;
        String configured = context.getEnvironment().getProperty("keel.manifest");
        return configured != null && !configured.isBlank() && loader.getResource(configured).exists();
    }
}
