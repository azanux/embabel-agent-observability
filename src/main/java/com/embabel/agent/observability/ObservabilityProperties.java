package com.embabel.agent.observability;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration properties for Embabel Agent observability.
 * Works with any OpenTelemetry-compatible exporter (Zipkin, OTLP, Langfuse, etc.).
 *
 * @author Quantpulsar 2025-2026
 */
@ConfigurationProperties(prefix = "embabel.observability")
public class ObservabilityProperties {

    /**
     * Default constructor.
     */
    public ObservabilityProperties() {
    }

    /** Tracing implementation type. */
    public enum Implementation {
        /** Spring Observation API - traces + metrics (recommended). - default */
        SPRING_OBSERVATION,
        /** Micrometer Tracing API - traces only. */
        MICROMETER_TRACING,
        /** OpenTelemetry API direct - traces only. */
        OPENTELEMETRY_DIRECT
    }

    /** Enable/disable observability. */
    private boolean enabled = true;

    /** Tracing implementation type. */
    private Implementation implementation = Implementation.SPRING_OBSERVATION;

    /** Service name for traces. */
    private String serviceName = "embabel-agent";

    /** Tracer instrumentation name. */
    private String tracerName = "embabel-agent";

    /** Tracer version. */
    private String tracerVersion = "0.3.3";

    /** Max attribute length before truncation. */
    private int maxAttributeLength = 4000;

    /** Trace agent events (agents, actions, goals). */
    private boolean traceAgentEvents = true;

    /** Trace tool calls. */
    private boolean traceToolCalls = true;

    /** Trace LLM calls. */
    private boolean traceLlmCalls = true;

    /** Trace planning events. */
    private boolean tracePlanning = true;

    /** Trace state transitions. */
    private boolean traceStateTransitions = true;

    /** Trace lifecycle states. */
    private boolean traceLifecycleStates = true;

    /** Trace object binding (verbose, disabled by default). */
    private boolean traceObjectBinding = false;

    // Getters and Setters

    /**
     * Returns whether observability is enabled.
     * @return true if enabled
     */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Sets whether observability is enabled.
     * @param enabled true to enable
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns the tracing implementation type.
     * @return the implementation
     */
    public Implementation getImplementation() {
        return implementation;
    }

    /**
     * Sets the tracing implementation type.
     * @param implementation the implementation to use
     */
    public void setImplementation(Implementation implementation) {
        this.implementation = implementation;
    }

    /**
     * Returns the service name for traces.
     * @return the service name
     */
    public String getServiceName() {
        return serviceName;
    }

    /**
     * Sets the service name for traces.
     * @param serviceName the service name
     */
    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    /**
     * Returns the tracer instrumentation name.
     * @return the tracer name
     */
    public String getTracerName() {
        return tracerName;
    }

    /**
     * Sets the tracer instrumentation name.
     * @param tracerName the tracer name
     */
    public void setTracerName(String tracerName) {
        this.tracerName = tracerName;
    }

    /**
     * Returns the tracer version.
     * @return the tracer version
     */
    public String getTracerVersion() {
        return tracerVersion;
    }

    /**
     * Sets the tracer version.
     * @param tracerVersion the tracer version
     */
    public void setTracerVersion(String tracerVersion) {
        this.tracerVersion = tracerVersion;
    }

    /**
     * Returns the max attribute length before truncation.
     * @return the max attribute length
     */
    public int getMaxAttributeLength() {
        return maxAttributeLength;
    }

    /**
     * Sets the max attribute length before truncation.
     * @param maxAttributeLength the max attribute length
     */
    public void setMaxAttributeLength(int maxAttributeLength) {
        this.maxAttributeLength = maxAttributeLength;
    }

    /**
     * Returns whether agent events tracing is enabled.
     * @return true if agent events are traced
     */
    public boolean isTraceAgentEvents() {
        return traceAgentEvents;
    }

    /**
     * Sets whether to trace agent events.
     * @param traceAgentEvents true to trace agent events
     */
    public void setTraceAgentEvents(boolean traceAgentEvents) {
        this.traceAgentEvents = traceAgentEvents;
    }

    /**
     * Returns whether tool calls tracing is enabled.
     * @return true if tool calls are traced
     */
    public boolean isTraceToolCalls() {
        return traceToolCalls;
    }

    /**
     * Sets whether to trace tool calls.
     * @param traceToolCalls true to trace tool calls
     */
    public void setTraceToolCalls(boolean traceToolCalls) {
        this.traceToolCalls = traceToolCalls;
    }

    /**
     * Returns whether LLM calls tracing is enabled.
     * @return true if LLM calls are traced
     */
    public boolean isTraceLlmCalls() {
        return traceLlmCalls;
    }

    /**
     * Sets whether to trace LLM calls.
     * @param traceLlmCalls true to trace LLM calls
     */
    public void setTraceLlmCalls(boolean traceLlmCalls) {
        this.traceLlmCalls = traceLlmCalls;
    }

    /**
     * Returns whether planning events tracing is enabled.
     * @return true if planning events are traced
     */
    public boolean isTracePlanning() {
        return tracePlanning;
    }

    /**
     * Sets whether to trace planning events.
     * @param tracePlanning true to trace planning events
     */
    public void setTracePlanning(boolean tracePlanning) {
        this.tracePlanning = tracePlanning;
    }

    /**
     * Returns whether state transitions tracing is enabled.
     * @return true if state transitions are traced
     */
    public boolean isTraceStateTransitions() {
        return traceStateTransitions;
    }

    /**
     * Sets whether to trace state transitions.
     * @param traceStateTransitions true to trace state transitions
     */
    public void setTraceStateTransitions(boolean traceStateTransitions) {
        this.traceStateTransitions = traceStateTransitions;
    }

    /**
     * Returns whether lifecycle states tracing is enabled.
     * @return true if lifecycle states are traced
     */
    public boolean isTraceLifecycleStates() {
        return traceLifecycleStates;
    }

    /**
     * Sets whether to trace lifecycle states.
     * @param traceLifecycleStates true to trace lifecycle states
     */
    public void setTraceLifecycleStates(boolean traceLifecycleStates) {
        this.traceLifecycleStates = traceLifecycleStates;
    }

    /**
     * Returns whether object binding tracing is enabled.
     * @return true if object binding is traced
     */
    public boolean isTraceObjectBinding() {
        return traceObjectBinding;
    }

    /**
     * Sets whether to trace object binding.
     * @param traceObjectBinding true to trace object binding
     */
    public void setTraceObjectBinding(boolean traceObjectBinding) {
        this.traceObjectBinding = traceObjectBinding;
    }
}
