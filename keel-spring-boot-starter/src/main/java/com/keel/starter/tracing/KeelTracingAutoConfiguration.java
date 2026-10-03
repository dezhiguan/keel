package com.keel.starter.tracing;

import com.keel.starter.manifest.AgentRegistry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class KeelTracingAutoConfiguration {
    @Bean(destroyMethod = "close")
    SdkTracerProvider keelTracerProvider() {
        var builder = SdkTracerProvider.builder();
        SpanExporter exporter = LangfuseSpanExporter.fromEnvironment();
        if (exporter != null) builder.addSpanProcessor(BatchSpanProcessor.builder(exporter).build());
        return builder.build();
    }

    @Bean(destroyMethod = "close")
    OpenTelemetry keelOpenTelemetry(SdkTracerProvider provider) {
        return OpenTelemetrySdk.builder().setTracerProvider(provider).build();
    }

    @Bean
    Tracer keelTracer(OpenTelemetry openTelemetry) {
        return openTelemetry.getTracer("keel.starter");
    }

    @Bean
    TraceIdResolver traceIdResolver() {
        return new TraceIdResolver();
    }

    @Bean
    AgentTracing agentTracing(Tracer tracer) {
        return new AgentTracing(tracer);
    }

    @Bean
    TraceHeaderPropagator traceHeaderPropagator(TraceIdResolver traceIdResolver) {
        return new TraceHeaderPropagator(traceIdResolver);
    }

    @Bean
    TracingMdcFilter tracingMdcFilter(TraceIdResolver traceIdResolver, Tracer tracer,
                                      ObjectProvider<AgentRegistry> registries,
                                      @Value("${spring.application.name:keel-agent}") String serviceName) {
        return new TracingMdcFilter(traceIdResolver, tracer, registries.getIfAvailable(), serviceName);
    }

    @Bean
    FilterRegistrationBean<TracingMdcFilter> tracingMdcFilterRegistration(TracingMdcFilter filter) {
        FilterRegistrationBean<TracingMdcFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        registration.addUrlPatterns("/*");
        return registration;
    }
}
