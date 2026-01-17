package com.embabel.agent.observability.observation;

import com.embabel.agent.observability.ObservabilityProperties;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests for EmbabelSpringObservationEventListener initialization.
 */
@ExtendWith(MockitoExtension.class)
class EmbabelSpringObservationEventListenerTest {

    @Mock
    private Tracer tracer;

    private ObservabilityProperties properties;

    @BeforeEach
    void setUp() {
        properties = new ObservabilityProperties();
    }

    @Test
    void constructor_shouldCreateListener() {
        EmbabelSpringObservationEventListener listener =
                new EmbabelSpringObservationEventListener(tracer, properties);

        assertThat(listener).isNotNull();
    }

    @Test
    void constructor_shouldAcceptCustomProperties() {
        properties.setMaxAttributeLength(2000);
        properties.setTraceToolCalls(false);
        properties.setTracePlanning(false);

        EmbabelSpringObservationEventListener listener =
                new EmbabelSpringObservationEventListener(tracer, properties);

        assertThat(listener).isNotNull();
    }
}
