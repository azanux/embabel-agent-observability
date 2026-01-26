package com.embabel.agent.observability;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for ObservabilityProperties configuration.
 */
class ObservabilityPropertiesTest {

    // Test default values are correctly set
    @Test
    void defaultValues_shouldBeCorrect() {
        ObservabilityProperties props = new ObservabilityProperties();

        assertThat(props.isEnabled()).isTrue();
        assertThat(props.getImplementation()).isEqualTo(ObservabilityProperties.Implementation.SPRING_OBSERVATION);
        assertThat(props.getServiceName()).isEqualTo("embabel-agent");
        assertThat(props.getTracerName()).isEqualTo("embabel-agent");
        assertThat(props.getTracerVersion()).isEqualTo("0.3.3");
        assertThat(props.getMaxAttributeLength()).isEqualTo(4000);
    }

    // Test trace flags default values
    @Test
    void traceFlags_shouldHaveCorrectDefaults() {
        ObservabilityProperties props = new ObservabilityProperties();

        assertThat(props.isTraceAgentEvents()).isTrue();
        assertThat(props.isTraceToolCalls()).isTrue();
        assertThat(props.isTraceLlmCalls()).isTrue();
        assertThat(props.isTracePlanning()).isTrue();
        assertThat(props.isTraceStateTransitions()).isTrue();
        assertThat(props.isTraceLifecycleStates()).isTrue();
        // Object binding disabled by default (verbose)
        assertThat(props.isTraceObjectBinding()).isFalse();
    }

    // Test setters work correctly
    @Test
    void setters_shouldUpdateValues() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setEnabled(false);
        props.setServiceName("custom-service");
        props.setMaxAttributeLength(1000);
        props.setTraceToolCalls(false);

        assertThat(props.isEnabled()).isFalse();
        assertThat(props.getServiceName()).isEqualTo("custom-service");
        assertThat(props.getMaxAttributeLength()).isEqualTo(1000);
        assertThat(props.isTraceToolCalls()).isFalse();
    }

    // Test implementation setter
    @Test
    void setImplementation_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setImplementation(ObservabilityProperties.Implementation.MICROMETER_TRACING);
        assertThat(props.getImplementation()).isEqualTo(ObservabilityProperties.Implementation.MICROMETER_TRACING);

        props.setImplementation(ObservabilityProperties.Implementation.OPENTELEMETRY_DIRECT);
        assertThat(props.getImplementation()).isEqualTo(ObservabilityProperties.Implementation.OPENTELEMETRY_DIRECT);
    }

    // Test all Implementation enum values exist
    @Test
    void implementationEnum_shouldHaveAllValues() {
        ObservabilityProperties.Implementation[] values = ObservabilityProperties.Implementation.values();

        assertThat(values).hasSize(3);
        assertThat(values).contains(
                ObservabilityProperties.Implementation.SPRING_OBSERVATION,
                ObservabilityProperties.Implementation.MICROMETER_TRACING,
                ObservabilityProperties.Implementation.OPENTELEMETRY_DIRECT
        );
    }

    // Test tracerName setter
    @Test
    void setTracerName_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setTracerName("custom-tracer");

        assertThat(props.getTracerName()).isEqualTo("custom-tracer");
    }

    // Test tracerVersion setter
    @Test
    void setTracerVersion_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setTracerVersion("1.0.0");

        assertThat(props.getTracerVersion()).isEqualTo("1.0.0");
    }

    // Test all trace flag setters
    @Test
    void setTraceAgentEvents_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setTraceAgentEvents(false);

        assertThat(props.isTraceAgentEvents()).isFalse();
    }

    @Test
    void setTraceLlmCalls_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setTraceLlmCalls(false);

        assertThat(props.isTraceLlmCalls()).isFalse();
    }

    @Test
    void setTracePlanning_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setTracePlanning(false);

        assertThat(props.isTracePlanning()).isFalse();
    }

    @Test
    void setTraceStateTransitions_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setTraceStateTransitions(false);

        assertThat(props.isTraceStateTransitions()).isFalse();
    }

    @Test
    void setTraceLifecycleStates_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setTraceLifecycleStates(false);

        assertThat(props.isTraceLifecycleStates()).isFalse();
    }

    @Test
    void setTraceObjectBinding_shouldUpdateValue() {
        ObservabilityProperties props = new ObservabilityProperties();

        props.setTraceObjectBinding(true);

        assertThat(props.isTraceObjectBinding()).isTrue();
    }

    // Test valueOf for Implementation enum
    @Test
    void implementationEnum_valueOf_shouldWork() {
        assertThat(ObservabilityProperties.Implementation.valueOf("SPRING_OBSERVATION"))
                .isEqualTo(ObservabilityProperties.Implementation.SPRING_OBSERVATION);
        assertThat(ObservabilityProperties.Implementation.valueOf("MICROMETER_TRACING"))
                .isEqualTo(ObservabilityProperties.Implementation.MICROMETER_TRACING);
        assertThat(ObservabilityProperties.Implementation.valueOf("OPENTELEMETRY_DIRECT"))
                .isEqualTo(ObservabilityProperties.Implementation.OPENTELEMETRY_DIRECT);
    }
}
