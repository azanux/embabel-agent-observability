package com.embabel.agent.observability;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor;
import io.opentelemetry.sdk.trace.export.SpanExporter;
import io.opentelemetry.semconv.ServiceAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureOrder;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;

import java.util.List;
import java.util.Objects;

/**
 * Auto-configuration for OpenTelemetry SDK with multi-exporter support.
 * Configures SdkTracerProvider and exports spans to backends (Langfuse, Zipkin, OTLP, etc.).
 *
 * <p>This configuration uses {@link AutoConfigureOrder} with low precedence to ensure
 * it runs AFTER all Spring Boot exporter auto-configurations (Zipkin, OTLP, etc.)
 * have created their {@link SpanExporter} beans. This is framework-agnostic and works
 * with any exporter that Spring Boot auto-configures.
 *
 * @author Quantpulsar 2025-2026
 * @see ObservabilityProperties
 */
@AutoConfiguration
@AutoConfigureOrder(Ordered.LOWEST_PRECEDENCE - 10)
@EnableConfigurationProperties(ObservabilityProperties.class)
@ConditionalOnClass({SdkTracerProvider.class, OpenTelemetry.class})
@ConditionalOnProperty(prefix = "embabel.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OpenTelemetrySdkAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(OpenTelemetrySdkAutoConfiguration.class);

    /**
     * Default constructor for OpenTelemetrySdkAutoConfiguration.
     */
    public OpenTelemetrySdkAutoConfiguration() {
    }

    /**
     * Creates the OpenTelemetry Resource with service name.
     *
     * @param properties the observability configuration properties
     * @return the configured OpenTelemetry Resource
     */
    @Bean
    @ConditionalOnMissingBean(Resource.class)
    public Resource openTelemetryResource(ObservabilityProperties properties) {
        return Resource.getDefault()
                .merge(Resource.create(Attributes.of(
                        ServiceAttributes.SERVICE_NAME, properties.getServiceName()
                )));
    }

    /**
     * Creates the SdkTracerProvider with all configured SpanExporters and SpanProcessors.
     *
     * @param exportersProvider provider for the list of SpanExporter beans
     * @param processorsProvider provider for the list of SpanProcessor beans
     * @param resource the OpenTelemetry Resource to associate with traces
     * @return the configured SdkTracerProvider, or null if no exporters are available
     */
    @Bean
    @ConditionalOnMissingBean(SdkTracerProvider.class)
    public SdkTracerProvider sdkTracerProvider(
            ObjectProvider<List<SpanExporter>> exportersProvider,
            ObjectProvider<List<SpanProcessor>> processorsProvider,
            Resource resource) {

        List<SpanExporter> exporters = exportersProvider.getIfAvailable();
        List<SpanProcessor> processors = processorsProvider.getIfAvailable();

        // Filter out null exporters
        List<SpanExporter> validExporters = exporters == null
                ? List.of()
                : exporters.stream()
                    .filter(Objects::nonNull)
                    .toList();

        // Filter out null processors
        List<SpanProcessor> validProcessors = processors == null
                ? List.of()
                : processors.stream()
                    .filter(Objects::nonNull)
                    .toList();

        if (validExporters.isEmpty()) {
            log.warn("No SpanExporter beans found. OpenTelemetry tracing will be disabled. " +
                    "To enable tracing, add an exporter dependency (e.g., opentelemetry-exporter-langfuse, " +
                    "opentelemetry-exporter-zipkin) and configure it properly.");
            return null;
        }

        // Build tracer provider
        SdkTracerProviderBuilder tracerProviderBuilder = SdkTracerProvider.builder()
                .setResource(resource);

        // Add custom processors first (e.g., LangfuseSpanProcessor for enrichment)
        for (SpanProcessor processor : validProcessors) {
            tracerProviderBuilder.addSpanProcessor(processor);
            log.debug("Added SpanProcessor: {}", processor.getClass().getSimpleName());
        }

        // Add exporters wrapped in BatchSpanProcessor
        for (SpanExporter exporter : validExporters) {
            tracerProviderBuilder.addSpanProcessor(
                    BatchSpanProcessor.builder(exporter).build()
            );
            log.debug("Added SpanExporter: {}", exporter.getClass().getSimpleName());
        }

        SdkTracerProvider tracerProvider = tracerProviderBuilder.build();

        log.info("SdkTracerProvider configured with {} processor(s) and {} exporter(s)",
                validProcessors.size(), validExporters.size());

        return tracerProvider;
    }

    /**
     * Creates the OpenTelemetry SDK instance and registers it globally.
     *
     * @param tracerProviderProvider provider for the SdkTracerProvider bean
     * @return the configured OpenTelemetry instance, or a noop instance if no tracer provider is available
     */
    @Bean
    @ConditionalOnMissingBean(OpenTelemetry.class)
    public OpenTelemetry openTelemetry(ObjectProvider<SdkTracerProvider> tracerProviderProvider) {
        SdkTracerProvider tracerProvider = tracerProviderProvider.getIfAvailable();

        if (tracerProvider == null) {
            log.warn("No SdkTracerProvider available. OpenTelemetry will be disabled.");
            return OpenTelemetry.noop();
        }

        // Build and register OpenTelemetry SDK globally
        OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .buildAndRegisterGlobal();

        log.info("OpenTelemetry SDK configured and registered globally");

        return openTelemetry;
    }
}
