package com.chattera.observability;

import io.micrometer.core.instrument.MeterRegistry;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;

/**
 * Tags every metric emitted by a Chattera service with a "service" common
 * tag, derived from {@code spring.application.name}. Minimal starter config
 * for Sprint 1's observability baseline; tracing/log-shipping config is
 * deferred until a backend (e.g. OpenTelemetry collector) is chosen with
 * devops-engineer.
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
public class ObservabilityAutoConfiguration {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> commonMeterTags(
            @Value("${spring.application.name:chattera-service}") String applicationName) {
        return registry -> registry.config().commonTags("service", applicationName);
    }
}
