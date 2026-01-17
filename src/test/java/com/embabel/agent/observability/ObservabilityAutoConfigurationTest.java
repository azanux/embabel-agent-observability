package com.embabel.agent.observability;

import com.embabel.agent.api.event.AgenticEventListener;
import com.embabel.agent.observability.observation.ChatModelObservationFilter;
import com.embabel.agent.observability.observation.EmbabelObservationEventListener;
import com.embabel.agent.observability.observation.EmbabelSpringObservationEventListener;
import io.micrometer.tracing.Tracer;
import io.opentelemetry.api.OpenTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests for ObservabilityAutoConfiguration.
 */
class ObservabilityAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ObservabilityAutoConfiguration.class));

    // Event listener bean created by default
    @Test
    void eventListener_shouldBeCreated_byDefault() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(EmbabelObservationEventListener.class);
                });
    }

    // Event listener not created when disabled
    @Test
    void eventListener_shouldNotBeCreated_whenDisabled() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfig.class)
                .withPropertyValues("embabel.observability.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(EmbabelObservationEventListener.class);
                });
    }

    // Event listener not created when trace-agent-events disabled
    @Test
    void eventListener_shouldNotBeCreated_whenTraceAgentEventsDisabled() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfig.class)
                .withPropertyValues("embabel.observability.trace-agent-events=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(EmbabelObservationEventListener.class);
                });
    }

    // ChatModel filter created when Spring AI on classpath
    @Test
    void chatModelFilter_shouldBeCreated_byDefault() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ChatModelObservationFilter.class);
                });
    }

    // ChatModel filter not created when disabled
    @Test
    void chatModelFilter_shouldNotBeCreated_whenDisabled() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfig.class)
                .withPropertyValues("embabel.observability.trace-llm-calls=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ChatModelObservationFilter.class);
                });
    }

    // Properties bean always created
    @Test
    void propertiesBean_shouldBeCreated() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ObservabilityProperties.class);
                });
    }

    // Custom property values applied
    @Test
    void properties_shouldApplyCustomValues() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfig.class)
                .withPropertyValues(
                        "embabel.observability.service-name=my-app",
                        "embabel.observability.max-attribute-length=2000"
                )
                .run(context -> {
                    ObservabilityProperties props = context.getBean(ObservabilityProperties.class);
                    assertThat(props.getServiceName()).isEqualTo("my-app");
                    assertThat(props.getMaxAttributeLength()).isEqualTo(2000);
                });
    }

    // Micrometer Tracing listener used when property set and tracer available
    @Test
    void eventListener_shouldUseMicrometerTracing_whenPropertySetAndTracerAvailable() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfig.class, TracerConfig.class)
                .withPropertyValues("embabel.observability.implementation=MICROMETER_TRACING")
                .run(context -> {
                    assertThat(context).hasSingleBean(AgenticEventListener.class);
                    assertThat(context.getBean(AgenticEventListener.class))
                            .isInstanceOf(EmbabelSpringObservationEventListener.class);
                });
    }

    // OpenTelemetry direct listener used when property set explicitly
    @Test
    void eventListener_shouldUseOpenTelemetryDirect_whenPropertySet() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfig.class, TracerConfig.class)
                .withPropertyValues("embabel.observability.implementation=OPENTELEMETRY_DIRECT")
                .run(context -> {
                    assertThat(context).hasSingleBean(AgenticEventListener.class);
                    assertThat(context.getBean(AgenticEventListener.class))
                            .isInstanceOf(EmbabelObservationEventListener.class);
                });
    }

    // Falls back to OpenTelemetry direct when Tracer not available
    @Test
    void eventListener_shouldFallbackToOpenTelemetry_whenNoTracer() {
        contextRunner
                .withUserConfiguration(OpenTelemetryConfig.class)
                .withPropertyValues("embabel.observability.implementation=MICROMETER_TRACING")
                .run(context -> {
                    assertThat(context).hasSingleBean(AgenticEventListener.class);
                    assertThat(context.getBean(AgenticEventListener.class))
                            .isInstanceOf(EmbabelObservationEventListener.class);
                });
    }

    // Mock OpenTelemetry bean for tests
    @Configuration
    static class OpenTelemetryConfig {
        @Bean
        OpenTelemetry openTelemetry() {
            return mock(OpenTelemetry.class);
        }
    }

    // Mock Tracer for tests
    @Configuration
    static class TracerConfig {
        @Bean
        Tracer tracer() {
            return mock(Tracer.class);
        }
    }
}
