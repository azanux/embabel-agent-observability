package com.embabel.agent.observability.observation;

import io.micrometer.observation.Observation;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.DefaultTracingObservationHandler;

/**
 * Tracing observation handler for standard (non-Embabel) contexts.
 * This handler explicitly excludes {@link EmbabelObservationContext} to prevent
 * conflicts with {@link EmbabelTracingObservationHandler}.
 *
 * <p>When both handlers are registered, this ensures:
 * <ul>
 *   <li>{@link EmbabelObservationContext} → handled by {@link EmbabelTracingObservationHandler}</li>
 *   <li>Standard {@link Observation.Context} → handled by this handler</li>
 * </ul>
 *
 * <p>This prevents the default handler from overwriting spans in TracingContext
 * that were created by EmbabelTracingObservationHandler.
 *
 * @author Quantpulsar 2025-2026
 */
public class NonEmbabelTracingObservationHandler extends DefaultTracingObservationHandler {

    /**
     * Creates a new handler.
     *
     * @param tracer the Micrometer tracer
     */
    public NonEmbabelTracingObservationHandler(Tracer tracer) {
        super(tracer);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportsContext(Observation.Context context) {
        // Handle ALL contexts EXCEPT EmbabelObservationContext
        // This allows EmbabelTracingObservationHandler to handle Embabel contexts exclusively
        return !(context instanceof EmbabelObservationContext);
    }
}