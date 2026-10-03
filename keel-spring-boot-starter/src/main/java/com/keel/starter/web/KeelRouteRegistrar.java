package com.keel.starter.web;

import com.keel.starter.annotation.KeelEntry;
import com.keel.starter.annotation.KeelAgent;
import com.keel.starter.manifest.AgentBinding;
import com.keel.starter.manifest.AgentRegistry;
import com.keel.starter.manifest.ManifestLoader;
import java.lang.reflect.Method;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;
import org.springframework.web.util.pattern.PathPattern;

public class KeelRouteRegistrar implements ApplicationRunner {
    private final AgentRegistry registry;
    private final ManifestLoader loader;
    private final InvokeController invoke;
    private final HealthController health;
    private final ManifestController manifest;
    private final FeedbackController feedback;
    private final ApplicationContext context;

    public KeelRouteRegistrar(AgentRegistry registry, ManifestLoader loader, InvokeController invoke,
                              HealthController health, ManifestController manifest, FeedbackController feedback,
                              ApplicationContext context) {
        this.registry = registry;
        this.loader = loader;
        this.invoke = invoke;
        this.health = health;
        this.manifest = manifest;
        this.feedback = feedback;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        Map<String, Object> beans = context.getBeansWithAnnotation(KeelAgent.class);
        if (beans.isEmpty()) {
            throw new IllegalStateException("A Keel manifest is present but no @KeelAgent bean was found");
        }
        for (Object bean : beans.values()) {
            Class<?> type = bean.getClass();
            KeelAgent marker = AnnotatedElementUtils.findMergedAnnotation(type, KeelAgent.class);
            if (marker == null) throw new IllegalStateException("Missing @KeelAgent on " + type.getName());
            Method entry = null;
            for (Method method : type.getDeclaredMethods()) {
                if (method.getAnnotation(KeelEntry.class) == null) continue;
                if (entry != null) throw new IllegalStateException("Only one @KeelEntry may be registered on " + type.getName());
                entry = method;
            }
            if (entry == null) throw new IllegalStateException("Missing @KeelEntry on " + type.getName());
            var loaded = loader.load(marker.manifest());
            loader.requireEnvironment(loaded);
            registry.add(new AgentBinding(loaded, bean, entry));
        }
        RequestMappingHandlerMapping mapping = context.getBean(RequestMappingHandlerMapping.class);
        for (AgentBinding binding : registry.all()) {
            String prefix = registry.prefix(binding);
            register(mapping, prefix + "/v1/invoke", RequestMethod.POST, invoke, "invoke");
            register(mapping, prefix + "/v1/health", RequestMethod.GET, health, "health");
            register(mapping, prefix + "/v1/manifest", RequestMethod.GET, manifest, "manifest");
            register(mapping, prefix + "/v1/feedback", RequestMethod.POST, feedback, "feedback");
            register(mapping, prefix + "/v1/runs/{run_id}", RequestMethod.GET, invoke, "run");
            register(mapping, prefix + "/v1/runs/{run_id}/resume", RequestMethod.POST, invoke, "run");
        }
    }

    private static void register(RequestMappingHandlerMapping mapping, String path, RequestMethod method,
                                 Object handler, String methodName) throws NoSuchMethodException {
        for (var existing : mapping.getHandlerMethods().keySet()) {
            var patterns = existing.getPathPatternsCondition();
            if (patterns == null) continue;
            for (PathPattern pattern : patterns.getPatterns()) {
                var methods = existing.getMethodsCondition().getMethods();
                if (pattern.getPatternString().equals(path) && (methods.isEmpty() || methods.contains(method))) {
                    throw new IllegalStateException("Route conflict: " + path);
                }
            }
        }
        Method target = handler.getClass().getMethod(methodName, jakarta.servlet.http.HttpServletRequest.class);
        RequestMappingInfo info = RequestMappingInfo.paths(path).methods(method).build();
        mapping.registerMapping(info, handler, target);
    }
}
