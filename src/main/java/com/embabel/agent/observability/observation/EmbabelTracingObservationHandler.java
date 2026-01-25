package com.embabel.agent.observability.observation;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.handler.TracingObservationHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Custom TracingObservationHandler for Embabel observations.
 * Creates root spans for agents and manages parent-child hierarchy for actions/tools.
 *
 * @author Quantpulsar 2025-2026
 */
public class EmbabelTracingObservationHandler
        implements TracingObservationHandler<EmbabelObservationContext> {

    private static final Logger log = LoggerFactory.getLogger(EmbabelTracingObservationHandler.class);

    private final Tracer tracer;
    private final io.opentelemetry.api.trace.Tracer otelTracer;

    private final Map<String, Span> activeAgentSpans = new ConcurrentHashMap<>();
    private final Map<String, Span> activeActionSpans = new ConcurrentHashMap<>();
    private final Map<Integer, Tracer.SpanInScope> activeScopes = new ConcurrentHashMap<>();

    /**
     * Creates a new handler.
     *
     * @param tracer     the Micrometer tracer
     * @param otelTracer the OpenTelemetry tracer
     */
    public EmbabelTracingObservationHandler(Tracer tracer, io.opentelemetry.api.trace.Tracer otelTracer) {
        this.tracer = tracer;
        this.otelTracer = otelTracer;
        log.info("EmbabelTracingObservationHandler initialized");
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Tracer getTracer() {
        return tracer;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean supportsContext(Observation.Context context) {
        return context instanceof EmbabelObservationContext;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onStart(EmbabelObservationContext context) {
        Span span;

        if (context.isRoot()) {
            // Create root span (new trace)
            span = createRootSpan(context.getName());
            log.debug("Created root span for agent: {} (runId: {})", context.getName(), context.getRunId());
        } else {
            // Child span with parent resolution
            Span parentSpan = resolveParentSpan(context);
            if (parentSpan != null) {
                span = tracer.nextSpan(parentSpan).name(context.getName());
            } else {
                span = tracer.nextSpan().name(context.getName());
            }
            span.start();
            log.debug("Created child span for {}: {} (runId: {})",
                    context.getEventType(), context.getName(), context.getRunId());
        }

        span.tag("embabel.event.type", context.getEventType().name().toLowerCase());
        span.tag("embabel.run_id", context.getRunId());
        getTracingContext(context).setSpan(span);
        trackActiveSpan(context, span);
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onScopeOpened(EmbabelObservationContext context) {
        Span span = getTracingContext(context).getSpan();
        if (span != null) {
            // Put span in thread-local for Spring AI integration
            Tracer.SpanInScope scope = tracer.withSpan(span);
            activeScopes.put(System.identityHashCode(context), scope);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onScopeClosed(EmbabelObservationContext context) {
        Tracer.SpanInScope scope = activeScopes.remove(System.identityHashCode(context));
        if (scope != null) {
            scope.close();
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onStop(EmbabelObservationContext context) {
        Span span = getRequiredSpan(context);

        for (KeyValue keyValue : context.getLowCardinalityKeyValues()) {
            span.tag(keyValue.getKey(), keyValue.getValue());
        }
        for (KeyValue keyValue : context.getHighCardinalityKeyValues()) {
            span.tag(keyValue.getKey(), keyValue.getValue());
        }
        if (context.getContextualName() != null) {
            span.name(context.getContextualName());
        }

        untrackActiveSpan(context);
        span.end();
        log.debug("Ended span for {}: {} (runId: {})",
                context.getEventType(), context.getName(), context.getRunId());
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void onError(EmbabelObservationContext context) {
        Throwable error = context.getError();
        if (error != null) {
            Span span = getTracingContext(context).getSpan();
            if (span != null) {
                span.error(error);
            }
        }
    }

    /**
     * Creates a root span with no parent by clearing context.
     *
     * @param name the span name
     * @return the created root span
     */
    private Span createRootSpan(String name) {
        Span rootSpan;
        try (Tracer.SpanInScope ignored = tracer.withSpan(null)) {
            rootSpan = tracer.nextSpan().name(name);
            rootSpan.start();
        }
        return rootSpan;
    }

    /**
     * Resolves the parent span from Embabel hierarchy or current tracer context.
     * For tool calls, uses current tracer span to integrate with Spring AI ChatClient.
     *
     * @param context the observation context
     * @return the parent span, or null if none
     */
    private Span resolveParentSpan(EmbabelObservationContext context) {
        String runId = context.getRunId();

        switch (context.getEventType()) {
            case ACTION:
                return activeAgentSpans.get(runId);

            case AGENT_PROCESS:
                if (context.getParentRunId() != null) {
                    return activeAgentSpans.get(context.getParentRunId());
                }
                return null;

            case TOOL_CALL:
                // For tool calls, prefer current tracer span (could be Spring AI ChatClient)
                // This ensures tools are nested under LLM calls
                Span currentSpan = tracer.currentSpan();
                if (currentSpan != null) {
                    return currentSpan;
                }
                // Fallback to Embabel hierarchy
                Span toolActionSpan = activeActionSpans.get(runId);
                if (toolActionSpan != null) {
                    return toolActionSpan;
                }
                return activeAgentSpans.get(runId);

            case GOAL:
            case PLANNING:
            case STATE_TRANSITION:
            case LIFECYCLE:
                Span actionSpan = activeActionSpans.get(runId);
                if (actionSpan != null) {
                    return actionSpan;
                }
                return activeAgentSpans.get(runId);

            default:
                return null;
        }
    }

    /**
     * Tracks active span for parent resolution.
     *
     * @param context the observation context
     * @param span    the span to track
     */
    private void trackActiveSpan(EmbabelObservationContext context, Span span) {
        switch (context.getEventType()) {
            case AGENT_PROCESS:
                activeAgentSpans.put(context.getRunId(), span);
                break;
            case ACTION:
                activeActionSpans.put(context.getRunId(), span);
                break;
            default:
                break;
        }
    }

    /**
     * Removes span from active tracking when observation stops.
     *
     * @param context the observation context
     */
    private void untrackActiveSpan(EmbabelObservationContext context) {
        switch (context.getEventType()) {
            case AGENT_PROCESS:
                activeAgentSpans.remove(context.getRunId());
                break;
            case ACTION:
                activeActionSpans.remove(context.getRunId());
                break;
            default:
                break;
        }
    }
}
