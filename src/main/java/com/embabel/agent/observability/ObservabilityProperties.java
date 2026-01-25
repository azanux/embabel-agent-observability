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
    private String tracerVersion = "0.3.2";

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

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Implementation getImplementation() {
        return implementation;
    }

    public void setImplementation(Implementation implementation) {
        this.implementation = implementation;
    }

    public String getServiceName() {
        return serviceName;
    }

    public void setServiceName(String serviceName) {
        this.serviceName = serviceName;
    }

    public String getTracerName() {
        return tracerName;
    }

    public void setTracerName(String tracerName) {
        this.tracerName = tracerName;
    }

    public String getTracerVersion() {
        return tracerVersion;
    }

    public void setTracerVersion(String tracerVersion) {
        this.tracerVersion = tracerVersion;
    }

    public int getMaxAttributeLength() {
        return maxAttributeLength;
    }

    public void setMaxAttributeLength(int maxAttributeLength) {
        this.maxAttributeLength = maxAttributeLength;
    }

    public boolean isTraceAgentEvents() {
        return traceAgentEvents;
    }

    public void setTraceAgentEvents(boolean traceAgentEvents) {
        this.traceAgentEvents = traceAgentEvents;
    }

    public boolean isTraceToolCalls() {
        return traceToolCalls;
    }

    public void setTraceToolCalls(boolean traceToolCalls) {
        this.traceToolCalls = traceToolCalls;
    }

    public boolean isTraceLlmCalls() {
        return traceLlmCalls;
    }

    public void setTraceLlmCalls(boolean traceLlmCalls) {
        this.traceLlmCalls = traceLlmCalls;
    }

    public boolean isTracePlanning() {
        return tracePlanning;
    }

    public void setTracePlanning(boolean tracePlanning) {
        this.tracePlanning = tracePlanning;
    }

    public boolean isTraceStateTransitions() {
        return traceStateTransitions;
    }

    public void setTraceStateTransitions(boolean traceStateTransitions) {
        this.traceStateTransitions = traceStateTransitions;
    }

    public boolean isTraceLifecycleStates() {
        return traceLifecycleStates;
    }

    public void setTraceLifecycleStates(boolean traceLifecycleStates) {
        this.traceLifecycleStates = traceLifecycleStates;
    }

    public boolean isTraceObjectBinding() {
        return traceObjectBinding;
    }

    public void setTraceObjectBinding(boolean traceObjectBinding) {
        this.traceObjectBinding = traceObjectBinding;
    }
}
