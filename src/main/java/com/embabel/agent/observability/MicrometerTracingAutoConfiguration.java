package com.embabel.agent.observability;

import com.embabel.agent.observability.observation.EmbabelTracingObservationHandler;
import com.embabel.agent.observability.observation.NonEmbabelTracingObservationHandler;
import io.micrometer.observation.ObservationPredicate;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.api.OpenTelemetry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.autoconfigure.observation.ObservationRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration for Micrometer Tracing bridge to OpenTelemetry.
 * Provides OtelCurrentTraceContext for context propagation between Micrometer and OpenTelemetry.
 *
 * @author Quantpulsar 2025-2026
 * @see OpenTelemetrySdkAutoConfiguration
 */
@AutoConfiguration(after = OpenTelemetrySdkAutoConfiguration.class)
@ConditionalOnClass({OtelTracer.class, OpenTelemetry.class, ObservationRegistry.class})
@ConditionalOnProperty(prefix = "embabel.observability", name = "enabled", havingValue = "true", matchIfMissing = true)
public class MicrometerTracingAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MicrometerTracingAutoConfiguration.class);

    public MicrometerTracingAutoConfiguration() {
    }

    /**
     * Creates OtelCurrentTraceContext for parent-child span propagation.
     */
    @Bean
    @ConditionalOnMissingBean(OtelCurrentTraceContext.class)
    public OtelCurrentTraceContext otelCurrentTraceContext() {
        log.debug("Creating OtelCurrentTraceContext for trace context propagation");
        return new OtelCurrentTraceContext();
    }

    /**
     * Registers EmbabelTracingObservationHandler for root span creation and hierarchy management.
     */
    @Bean
    @ConditionalOnProperty(prefix = "embabel.observability", name = "implementation",
            havingValue = "SPRING_OBSERVATION", matchIfMissing = true)
    public ObservationRegistryCustomizer<ObservationRegistry> embabelTracingObservationCustomizer(
            ObjectProvider<Tracer> tracerProvider,
            ObjectProvider<OpenTelemetry> otelProvider,
            ObservabilityProperties properties) {

        return registry -> {
            Tracer tracer = tracerProvider.getIfAvailable();
            OpenTelemetry otel = otelProvider.getIfAvailable();

            if (tracer == null || otel == null) {
                log.warn("Cannot register EmbabelTracingObservationHandler: Tracer or OpenTelemetry not available");
                return;
            }

            io.opentelemetry.api.trace.Tracer otelTracer = otel.getTracer(
                    properties.getTracerName(),
                    properties.getTracerVersion()
            );

            EmbabelTracingObservationHandler handler = new EmbabelTracingObservationHandler(tracer, otelTracer);
            registry.observationConfig().observationHandler(handler);

            log.info("Registered EmbabelTracingObservationHandler for Spring Observation API integration");
        };
    }

    /**
     * Replaces Spring Boot's DefaultTracingObservationHandler with NonEmbabelTracingObservationHandler.
     *
     * <p><b>Why this is needed:</b> Spring Boot Actuator's {@code MicrometerTracingAutoConfiguration}
     * creates a {@code DefaultTracingObservationHandler} that handles ALL observation contexts,
     * including {@link com.embabel.agent.observability.observation.EmbabelObservationContext}.
     * This causes conflicts because both handlers process the same context, and the default handler
     * overwrites spans created by {@link EmbabelTracingObservationHandler}.
     *
     * <p><b>Solution:</b> By declaring a bean of type {@code DefaultTracingObservationHandler},
     * we prevent Spring Boot from creating its own (due to {@code @ConditionalOnMissingBean}).
     * Our {@link NonEmbabelTracingObservationHandler} extends {@code DefaultTracingObservationHandler}
     * but excludes {@code EmbabelObservationContext}, ensuring:
     * <ul>
     *   <li>{@code EmbabelObservationContext} → handled exclusively by {@link EmbabelTracingObservationHandler}</li>
     *   <li>All other contexts (Spring AI, HTTP, etc.) → handled by this handler</li>
     * </ul>
     *
     * @see <a href="https://github.com/spring-projects/spring-boot/blob/v3.1.5/spring-boot-project/spring-boot-actuator-autoconfigure/src/main/java/org/springframework/boot/actuate/autoconfigure/tracing/MicrometerTracingAutoConfiguration.java">
     *      Spring Boot MicrometerTracingAutoConfiguration</a>
     */
    @Bean
    @ConditionalOnMissingBean(DefaultTracingObservationHandler.class)
    @ConditionalOnProperty(prefix = "embabel.observability", name = "implementation",
            havingValue = "SPRING_OBSERVATION", matchIfMissing = true)
    public DefaultTracingObservationHandler defaultTracingObservationHandler(Tracer tracer) {
        log.info("Replacing Spring Boot's DefaultTracingObservationHandler with NonEmbabelTracingObservationHandler");
        return new NonEmbabelTracingObservationHandler(tracer);
    }

    /**
     * Observation name used by ObservabilityToolCallback from embabel-agent-api.
     * This is the observation that wraps tool calls at the Spring AI level.
     */
    private static final String OBSERVABILITY_TOOL_CALLBACK_OBSERVATION_NAME = "tool call";

    /**
     * Registers an ObservationPredicate to skip tool call observations from ObservabilityToolCallback
     * when Embabel's own tool tracing is enabled (trace-tool-calls=true).
     *
     * <p><b>Why this is needed:</b> When {@code trace-tool-calls=true}, the
     * {@link com.embabel.agent.observability.observation.EmbabelFullObservationEventListener}
     * (or other Embabel listeners) create their own tool spans via {@code ToolCallRequestEvent}
     * and {@code ToolCallResponseEvent}. Meanwhile, {@code ObservabilityToolCallback} from
     * embabel-agent-api also creates observations with name "tool call" for every tool invocation.
     *
     * <p>This results in duplicate tool spans:
     * <ul>
     *   <li>One from ObservabilityToolCallback ("tool call")</li>
     *   <li>One from EmbabelObservationEventListener ("tool:{toolName}")</li>
     * </ul>
     *
     * <p><b>Solution:</b> When {@code trace-tool-calls=true}, this predicate blocks the
     * "tool call" observations from ObservabilityToolCallback, leaving only the Embabel
     * tool spans which have richer semantic attributes and proper hierarchy integration.
     *
     * <p>When {@code trace-tool-calls=false}, this bean is not created, allowing
     * ObservabilityToolCallback observations to be traced normally.
     *
     * @return an ObservationRegistryCustomizer that registers the predicate to filter out "tool call" observations
     */
    @Bean
    @ConditionalOnProperty(prefix = "embabel.observability", name = "trace-tool-calls",
            havingValue = "true", matchIfMissing = true)
    public ObservationRegistryCustomizer<ObservationRegistry> skipObservabilityToolCallbackCustomizer() {
        log.info("Registering ObservationPredicate to skip ObservabilityToolCallback observations " +
                "(trace-tool-calls=true, Embabel will trace tools via events)");
        return registry -> registry.observationConfig().observationPredicate(
                (name, context) -> !OBSERVABILITY_TOOL_CALLBACK_OBSERVATION_NAME.equals(name)
        );
    }
}
