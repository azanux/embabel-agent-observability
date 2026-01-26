package com.embabel.agent.observability;

import com.embabel.agent.api.event.AgenticEventListener;
import com.embabel.agent.observability.observation.ChatModelObservationFilter;
import com.embabel.agent.observability.observation.EmbabelFullObservationEventListener;
import com.embabel.agent.observability.observation.EmbabelObservationEventListener;
import com.embabel.agent.observability.observation.EmbabelSpringObservationEventListener;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.OpenTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for Embabel Agent observability.
 * Configures tracing infrastructure with fallback: SPRING_OBSERVATION -> MICROMETER_TRACING -> OPENTELEMETRY_DIRECT.
 *
 * @author Quantpulsar 2025-2026
 * @see ObservabilityProperties
 */
@AutoConfiguration(
        after = MicrometerTracingAutoConfiguration.class,
        afterName = {
                "org.springframework.boot.actuate.autoconfigure.tracing.MicrometerTracingAutoConfiguration",
                "org.springframework.boot.actuate.autoconfigure.observation.ObservationAutoConfiguration"
        }
)
@EnableConfigurationProperties(ObservabilityProperties.class)
@ConditionalOnProperty(prefix = "embabel.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
public class ObservabilityAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(ObservabilityAutoConfiguration.class);

    /**
     * Default constructor.
     */
    public ObservabilityAutoConfiguration() {
    }

    /**
     * Creates the event listener based on configured implementation with automatic fallback.
     *
     * @param openTelemetryProvider the OpenTelemetry provider
     * @param tracerProvider the Micrometer Tracer provider
     * @param observationRegistryProvider the ObservationRegistry provider
     * @param properties the observability properties
     * @return the configured event listener
     */
    @Bean
    @ConditionalOnProperty(prefix = "embabel.observability", name = "trace-agent-events", havingValue = "true", matchIfMissing = true)
    public AgenticEventListener embabelObservationEventListener(
            ObjectProvider<OpenTelemetry> openTelemetryProvider,
            ObjectProvider<Tracer> tracerProvider,
            ObjectProvider<ObservationRegistry> observationRegistryProvider,
            ObservabilityProperties properties) {

        // Select implementation based on config
        if (properties.getImplementation() == ObservabilityProperties.Implementation.SPRING_OBSERVATION) {
            ObservationRegistry observationRegistry = observationRegistryProvider.getIfAvailable();
            if (observationRegistry != null) {
                log.info("Configuring Embabel Agent observability with Spring Observation API (traces + metrics)");
                return new EmbabelFullObservationEventListener(observationRegistry, properties);
            } else {
                log.warn("ObservationRegistry not found, falling back to Micrometer Tracing implementation");
            }
        }

        if (properties.getImplementation() == ObservabilityProperties.Implementation.MICROMETER_TRACING
                || properties.getImplementation() == ObservabilityProperties.Implementation.SPRING_OBSERVATION) {
            Tracer tracer = tracerProvider.getIfAvailable();
            if (tracer != null) {
                log.info("Configuring Embabel Agent observability with Micrometer Tracing (integrated with Spring AI)");
                return new EmbabelSpringObservationEventListener(tracer, properties);
            } else {
                log.warn("Micrometer Tracer not found, falling back to OpenTelemetry direct implementation");
            }
        }

        // OpenTelemetry direct (fallback or explicit choice)
        log.info("Configuring Embabel Agent observability with OpenTelemetry direct");
        return new EmbabelObservationEventListener(openTelemetryProvider, properties);
    }

    /**
     * Creates filter to enrich Spring AI LLM observations with prompt/completion.
     *
     * @param properties the observability properties
     * @return the configured observation filter
     */
    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = "org.springframework.ai.chat.observation.ChatModelObservationContext")
    @ConditionalOnProperty(prefix = "embabel.observability", name = "trace-llm-calls", havingValue = "true", matchIfMissing = true)
    public ChatModelObservationFilter chatModelObservationFilter(ObservabilityProperties properties) {
        log.debug("Configuring ChatModel observation filter for LLM call tracing");
        return new ChatModelObservationFilter(properties.getMaxAttributeLength());
    }
}
