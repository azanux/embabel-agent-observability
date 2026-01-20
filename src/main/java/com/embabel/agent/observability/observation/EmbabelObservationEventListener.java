package com.embabel.agent.observability.observation;

import com.embabel.agent.api.event.*;
import com.embabel.agent.core.Action;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Blackboard;
import com.embabel.agent.core.IoBinding;
import com.embabel.agent.core.ToolGroupMetadata;
import com.embabel.agent.observability.ObservabilityProperties;
import com.embabel.plan.Plan;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Event listener that traces Embabel Agent events using OpenTelemetry API directly.
 *
 * <p>This listener uses OpenTelemetry's {@link Tracer}, {@link Span}, and {@link Scope}
 * directly, which ensures proper context propagation for Spring AI LLM calls to appear
 * as children of Embabel action spans.
 *
 * <p>The key to hierarchical tracing is using {@code span.makeCurrent()} which sets
 * the span in the thread-local {@link Context}. Spring AI uses {@code Context.current()}
 * to find the parent span, so this approach ensures LLM calls are properly nested.
 *
 * <p>Traced events include:
 * <ul>
 *   <li>Agent process lifecycle (creation, completion, failure)</li>
 *   <li>Action execution (start, result)</li>
 *   <li>Goal achievement</li>
 *   <li>Tool calls (request, response)</li>
 *   <li>Planning events (ready to plan, plan formulated)</li>
 *   <li>State transitions</li>
 *   <li>Lifecycle states (WAITING, PAUSED, STUCK)</li>
 *   <li>Object binding events</li>
 * </ul>
 *
 * @see ObservabilityProperties
 */
public class EmbabelObservationEventListener implements AgenticEventListener, SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(EmbabelObservationEventListener.class);

    private final ObjectProvider<OpenTelemetry> openTelemetryProvider;
    private final ObservabilityProperties properties;

    /**
     * The tracer instance, initialized after all beans are created.
     * Will be null if no OpenTelemetry bean is available.
     */
    private Tracer tracer;

    private final Map<String, SpanContext> activeSpans = new ConcurrentHashMap<>();
    private final Map<String, String> inputSnapshots = new ConcurrentHashMap<>();
    private final Map<String, Integer> planIterations = new ConcurrentHashMap<>();

    /**
     * Record to hold both the Span and its Scope together.
     * The Scope must be closed before the Span ends to properly restore the previous context.
     */
    private record SpanContext(Span span, Scope scope) {}

    /**
     * Creates a new event listener.
     *
     * @param openTelemetryProvider provider for OpenTelemetry instance
     * @param properties observability configuration properties
     */
    public EmbabelObservationEventListener(
            ObjectProvider<OpenTelemetry> openTelemetryProvider,
            ObservabilityProperties properties) {
        this.openTelemetryProvider = openTelemetryProvider;
        this.properties = properties;
        log.debug("EmbabelObservationEventListener created, tracer will be initialized after context startup");
    }

    /**
     * Called after all singleton beans have been instantiated.
     * This ensures the OpenTelemetry bean from any exporter (Langfuse, Zipkin, etc.)
     * is available regardless of auto-configuration ordering.
     */
    @Override
    public void afterSingletonsInstantiated() {
        OpenTelemetry openTelemetry = openTelemetryProvider.getIfAvailable();
        if (openTelemetry != null) {
            this.tracer = openTelemetry.getTracer(
                    properties.getTracerName(),
                    properties.getTracerVersion()
            );
            log.info("EmbabelObservationEventListener initialized with OpenTelemetry tracer: {}",
                    properties.getTracerName());
        } else {
            log.warn("No OpenTelemetry bean found. Embabel agent tracing will be disabled. " +
                    "To enable tracing, add an OpenTelemetry exporter dependency (e.g., Langfuse, Zipkin, or Spring Boot OTLP).");
        }
    }

    /**
     * Checks if tracing is enabled (tracer is available).
     */
    private boolean isTracingEnabled() {
        return tracer != null;
    }

    @Override
    public void onProcessEvent(@NotNull AgentProcessEvent event) {
        // Early exit if tracing is not enabled (no OpenTelemetry bean available)
        if (!isTracingEnabled()) {
            return;
        }

        switch (event) {
            case AgentProcessCreationEvent e -> onAgentProcessCreation(e);
            case AgentProcessCompletedEvent e -> onAgentProcessCompleted(e);
            case AgentProcessFailedEvent e -> onAgentProcessFailed(e);
            case ActionExecutionStartEvent e -> onActionStart(e);
            case ActionExecutionResultEvent e -> onActionResult(e);
            case GoalAchievedEvent e -> onGoalAchieved(e);
            case ToolCallRequestEvent e -> {
                if (properties.isTraceToolCalls()) onToolCallRequest(e);
            }
            case ToolCallResponseEvent e -> {
                if (properties.isTraceToolCalls()) onToolCallResponse(e);
            }
            case AgentProcessReadyToPlanEvent e -> {
                if (properties.isTracePlanning()) onReadyToPlan(e);
            }
            case AgentProcessPlanFormulatedEvent e -> {
                if (properties.isTracePlanning()) onPlanFormulated(e);
            }
            case StateTransitionEvent e -> {
                if (properties.isTraceStateTransitions()) onStateTransition(e);
            }
            case AgentProcessWaitingEvent e -> {
                if (properties.isTraceLifecycleStates()) onLifecycleState(e, "WAITING");
            }
            case AgentProcessPausedEvent e -> {
                if (properties.isTraceLifecycleStates()) onLifecycleState(e, "PAUSED");
            }
            case AgentProcessStuckEvent e -> {
                if (properties.isTraceLifecycleStates()) onLifecycleState(e, "STUCK");
            }
            case ObjectAddedEvent e -> {
                if (properties.isTraceObjectBinding()) onObjectAdded(e);
            }
            case ObjectBoundEvent e -> {
                if (properties.isTraceObjectBinding()) onObjectBound(e);
            }
            default -> {}
        }
    }

    // ==================== Agent Lifecycle ====================

    private void onAgentProcessCreation(AgentProcessCreationEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String agentName = process.getAgent().getName();
        String parentId = process.getParentId();
        boolean isSubagent = parentId != null && !parentId.isEmpty();

        String goalName = extractGoalName(process);
        String plannerType = process.getProcessOptions().getPlannerType().name();
        String input = getBlackboardSnapshot(process);

        // Determine parent context for span hierarchy
        // Root agents use Context.root() to start a new independent trace
        // Subagents inherit from their parent agent's context
        Context parentContext;
        if (isSubagent) {
            SpanContext parentCtx = activeSpans.get("agent:" + parentId);
            if (parentCtx != null) {
                parentContext = Context.current().with(parentCtx.span);
            } else {
                // Parent not found, start new trace
                parentContext = Context.root();
            }
        } else {
            // Root agent: always start a new independent trace
            parentContext = Context.root();
        }

        Span span = tracer.spanBuilder(agentName)
                .setParent(parentContext)
                .setSpanKind(isSubagent ? SpanKind.INTERNAL : SpanKind.SERVER)
                .setAttribute("name", agentName)
                .setAttribute("gen_ai.operation.name", "agent")
                .setAttribute("gen_ai.conversation.id", runId)
                .setAttribute("embabel.agent.name", agentName)
                .setAttribute("embabel.agent.run_id", runId)
                .setAttribute("embabel.agent.goal", goalName)
                .setAttribute("embabel.agent.is_subagent", isSubagent)
                .setAttribute("embabel.agent.parent_id", parentId != null ? parentId : "")
                .setAttribute("embabel.agent.planner_type", plannerType)
                .setAttribute("embabel.event.type", "agent_process")
                .startSpan();

        if (!input.isEmpty()) {
            span.setAttribute("input.value", truncate(input));
            span.setAttribute("embabel.agent.input", truncate(input));
            inputSnapshots.put("agent:" + runId, input);
        }

        // Initialize plan iteration counter for this agent
        planIterations.put(runId, 0);

        // CRITICAL: makeCurrent() sets this span in thread-local context
        // This allows Spring AI to find it via Context.current()
        Scope scope = span.makeCurrent();
        activeSpans.put("agent:" + runId, new SpanContext(span, scope));

        log.debug("Started span for agent: {} (runId: {})", agentName, runId);
    }

    private void onAgentProcessCompleted(AgentProcessCompletedEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String key = "agent:" + runId;

        SpanContext ctx = activeSpans.remove(key);
        inputSnapshots.remove(key);
        planIterations.remove(runId);

        if (ctx != null) {
            ctx.span.setAttribute("embabel.agent.status", "completed");

            String output = getBlackboardSnapshot(process);
            if (!output.isEmpty()) {
                ctx.span.setAttribute("output.value", truncate(output));
            }

            Object lastResult = process.getBlackboard().lastResult();
            if (lastResult != null) {
                ctx.span.setAttribute("embabel.agent.result", truncate(lastResult.toString()));
            }

            ctx.span.setStatus(StatusCode.OK);
            // CRITICAL: Close scope BEFORE ending span to restore previous context
            ctx.scope.close();
            ctx.span.end();

            log.debug("Completed span for agent runId: {}", runId);
        }
    }

    private void onAgentProcessFailed(AgentProcessFailedEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String key = "agent:" + runId;

        SpanContext ctx = activeSpans.remove(key);
        inputSnapshots.remove(key);
        planIterations.remove(runId);

        if (ctx != null) {
            ctx.span.setAttribute("embabel.agent.status", "failed");

            Object failureInfo = process.getFailureInfo();
            if (failureInfo != null) {
                ctx.span.setAttribute("embabel.agent.error", truncate(failureInfo.toString()));
            }

            ctx.span.setStatus(StatusCode.ERROR, "Agent process failed");
            ctx.scope.close();
            ctx.span.end();

            log.debug("Failed span for agent runId: {}", runId);
        }
    }

    // ==================== Actions ====================

    private void onActionStart(ActionExecutionStartEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        Action action = event.getAction();
        String actionName = action.getName();
        String shortName = action.shortName();
        String input = getActionInputs(action, process);

        // Get parent context from agent span
        SpanContext parentCtx = activeSpans.get("agent:" + runId);
        Context parentContext = parentCtx != null ? Context.current().with(parentCtx.span) : Context.current();

        Span span = tracer.spanBuilder(shortName)
                .setParent(parentContext)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("name", shortName)
                .setAttribute("gen_ai.operation.name", "execute_action")
                .setAttribute("embabel.action.name", actionName)
                .setAttribute("embabel.action.short_name", shortName)
                .setAttribute("embabel.action.run_id", runId)
                .setAttribute("embabel.action.description", event.getAction().getDescription())
                .setAttribute("embabel.event.type", "action")
                .startSpan();

        String key = "action:" + runId + ":" + actionName;
        if (!input.isEmpty()) {
            span.setAttribute("input.value", truncate(input));
            inputSnapshots.put(key, input);
        }

        // CRITICAL: makeCurrent() so Spring AI LLM calls become children
        Scope scope = span.makeCurrent();
        activeSpans.put(key, new SpanContext(span, scope));

        log.debug("Started span for action: {} (runId: {})", shortName, runId);
    }

    private void onActionResult(ActionExecutionResultEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String actionName = event.getAction().getName();
        String key = "action:" + runId + ":" + actionName;

        SpanContext ctx = activeSpans.remove(key);
        inputSnapshots.remove(key);

        if (ctx != null) {
            String statusName = event.getActionStatus().getStatus().name();
            ctx.span.setAttribute("embabel.action.status", statusName);
            ctx.span.setAttribute("embabel.action.duration_ms", event.getRunningTime().toMillis());

            String output = getBlackboardSnapshot(process);
            if (!output.isEmpty()) {
                ctx.span.setAttribute("output.value", truncate(output));
            }

            Object lastResult = process.getBlackboard().lastResult();
            if (lastResult != null) {
                ctx.span.setAttribute("embabel.action.result", truncate(lastResult.toString()));
            }

            ctx.span.setStatus(StatusCode.OK);
            ctx.scope.close();
            ctx.span.end();

            log.debug("Completed span for action: {} (runId: {})", actionName, runId);
        }
    }

    // ==================== Goals ====================

    private void onGoalAchieved(GoalAchievedEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String goalName = event.getGoal().getName();

        String shortGoalName = goalName.contains(".")
                ? goalName.substring(goalName.lastIndexOf('.') + 1)
                : goalName;

        SpanContext parentCtx = activeSpans.get("agent:" + runId);
        Context parentContext = parentCtx != null ? Context.current().with(parentCtx.span) : Context.current();

        Span span = tracer.spanBuilder("goal:" + shortGoalName)
                .setParent(parentContext)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("name", shortGoalName)
                .setAttribute("embabel.goal.name", goalName)
                .setAttribute("embabel.goal.short_name", shortGoalName)
                .setAttribute("embabel.event.type", "goal_achieved")
                .startSpan();

        String snapshot = getBlackboardSnapshot(process);
        if (!snapshot.isEmpty()) {
            span.setAttribute("input.value", truncate(snapshot));
        }

        Object lastResult = process.getBlackboard().lastResult();
        if (lastResult != null) {
            span.setAttribute("output.value", truncate(lastResult.toString()));
        }

        if (event.getWorldState() != null) {
            span.setAttribute("embabel.goal.world_state", truncate(event.getWorldState().infoString(true, 0)));
        }

        span.setStatus(StatusCode.OK);
        span.end();

        log.debug("Recorded goal achieved: {} (runId: {})", shortGoalName, runId);
    }

    // ==================== Tools ====================

    /**
     * Starts span for tool invocation.
     * Uses current context span as parent to integrate with Spring AI ChatClient.
     */
    private void onToolCallRequest(ToolCallRequestEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String toolName = event.getTool();

        // Use current context as parent (could be Spring AI ChatClient span)
        // This ensures tool calls are nested under LLM calls
        Span currentSpan = Span.current();
        Context parentContext;

        if (currentSpan != null && currentSpan.getSpanContext().isValid()) {
            // Parent is the current span (ChatClient or Action)
            parentContext = Context.current();
            log.debug("Tool {} will be child of current span: {}", toolName, currentSpan.getSpanContext().getSpanId());
        } else {
            // Fallback to Embabel hierarchy
            SpanContext parentCtx = findParentSpan(runId);
            parentContext = parentCtx != null ? Context.current().with(parentCtx.span) : Context.current();
            log.debug("Tool {} using Embabel hierarchy as parent", toolName);
        }

        String correlationId = event.getCorrelationId();

        io.opentelemetry.api.trace.SpanBuilder spanBuilder = tracer.spanBuilder("tool:" + toolName)
                .setParent(parentContext)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("name", toolName)
                .setAttribute("gen_ai.operation.name", "execute_tool")
                .setAttribute("gen_ai.tool.name", toolName)
                .setAttribute("gen_ai.tool.type", "function")
                .setAttribute("embabel.tool.name", toolName)
                .setAttribute("embabel.event.type", "tool_call");

        // Add correlation ID for tracing tool calls across systems
        if (correlationId != null && !"-".equals(correlationId)) {
            spanBuilder.setAttribute("embabel.tool.correlation_id", correlationId);
        }

        // Add tool group metadata if available (description, role, etc.)
        ToolGroupMetadata metadata = event.getToolGroupMetadata();
        if (metadata != null) {
            String description = metadata.getDescription();
            if (description != null && !description.isEmpty()) {
                spanBuilder.setAttribute("gen_ai.tool.description", truncate(description));
            }
            String groupName = metadata.getName();
            if (groupName != null) {
                spanBuilder.setAttribute("embabel.tool.group.name", groupName);
            }
            String role = metadata.getRole();
            if (role != null) {
                spanBuilder.setAttribute("embabel.tool.group.role", role);
            }
        }

        Span span = spanBuilder.startSpan();

        if (event.getToolInput() != null) {
            span.setAttribute("input.value", truncate(event.getToolInput()));
            span.setAttribute("gen_ai.tool.call.arguments", truncate(event.getToolInput()));
        }

        Scope scope = span.makeCurrent();
        activeSpans.put("tool:" + runId + ":" + toolName, new SpanContext(span, scope));

        log.debug("Started span for tool: {} (runId: {}, correlationId: {})",
                toolName, runId, correlationId);
    }

    /**
     * Completes tool span with duration and output.
     * Captures tool output from event.getResult() using reflection (Kotlin mangled method).
     */
    private void onToolCallResponse(ToolCallResponseEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String toolName = event.getRequest().getTool();
        String key = "tool:" + runId + ":" + toolName;

        SpanContext ctx = activeSpans.remove(key);

        if (ctx != null) {
            ctx.span.setAttribute("embabel.tool.duration_ms", event.getRunningTime().toMillis());

            // Extract tool result using reflection (Kotlin Result has mangled method names)
            Object toolResult = extractToolResult(event);
            if (toolResult != null) {
                String truncatedResult = truncate(toolResult.toString());
                ctx.span.setAttribute("output.value", truncatedResult);
                ctx.span.setAttribute("gen_ai.tool.call.result", truncatedResult);
                ctx.span.setAttribute("embabel.tool.status", "success");
                ctx.span.setStatus(StatusCode.OK);
            } else {
                // Check if there was an error
                Throwable error = extractToolError(event);
                if (error != null) {
                    ctx.span.setAttribute("embabel.tool.status", "error");
                    ctx.span.setAttribute("embabel.tool.error.type", error.getClass().getSimpleName());
                    ctx.span.setAttribute("embabel.tool.error.message", truncate(error.getMessage()));
                    ctx.span.setStatus(StatusCode.ERROR, error.getMessage());
                    ctx.span.recordException(error);
                } else {
                    ctx.span.setAttribute("embabel.tool.status", "success");
                    ctx.span.setStatus(StatusCode.OK);
                }
            }

            ctx.scope.close();
            ctx.span.end();

            log.debug("Completed span for tool: {} (runId: {})", toolName, runId);
        }
    }

    /**
     * Extracts the successful result from a Kotlin Result.
     * Calls event.getResult().getOrNull() via reflection.
     *
     * Based on working Java example:
     * String outputJson = response.getResult().getOrNull();
     */
    private Object extractToolResult(ToolCallResponseEvent event) {
        try {
            // Find getResult method - Kotlin mangles it as getResult-XXXXX for value classes
            java.lang.reflect.Method getResultMethod = null;
            for (java.lang.reflect.Method m : ToolCallResponseEvent.class.getMethods()) {
                if (m.getName().startsWith("getResult") && m.getParameterCount() == 0) {
                    getResultMethod = m;
                    break;
                }
            }

            if (getResultMethod == null) {
                log.trace("getResult method not found on ToolCallResponseEvent");
                return null;
            }

            Object result = getResultMethod.invoke(event);

            if (result == null) {
                return null;
            }

            // Kotlin's Result<T> is an inline/value class - at runtime it's unboxed
            // So getResult() returns the actual value directly (e.g., String "74")
            // NOT a Result wrapper object. Just return it directly.
            // Only try getOrNull() if the result has that method (for backward compatibility)
            try {
                java.lang.reflect.Method getOrNullMethod = result.getClass().getMethod("getOrNull");
                return getOrNullMethod.invoke(result);
            } catch (NoSuchMethodException e) {
                // Result is already the unwrapped value (inline class behavior)
                return result;
            }
        } catch (Exception e) {
            log.trace("Could not extract tool result: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Extracts the error from a Kotlin Result.
     * Calls event.getResult().exceptionOrNull() via reflection.
     */
    private Throwable extractToolError(ToolCallResponseEvent event) {
        try {
            // Find getResult method - Kotlin mangles it as getResult-XXXXX for value classes
            java.lang.reflect.Method getResultMethod = null;
            for (java.lang.reflect.Method m : ToolCallResponseEvent.class.getMethods()) {
                if (m.getName().startsWith("getResult") && m.getParameterCount() == 0) {
                    getResultMethod = m;
                    break;
                }
            }

            if (getResultMethod == null) {
                log.trace("getResult method not found on ToolCallResponseEvent");
                return null;
            }

            Object result = getResultMethod.invoke(event);

            if (result == null) {
                return null;
            }

            // Check if result is already a Throwable (inline class unwrapped to error)
            if (result instanceof Throwable) {
                return (Throwable) result;
            }

            // Try exceptionOrNull() if available (for wrapped Result objects)
            try {
                java.lang.reflect.Method exceptionOrNullMethod = result.getClass().getMethod("exceptionOrNull");
                Object error = exceptionOrNullMethod.invoke(result);
                if (error instanceof Throwable) {
                    return (Throwable) error;
                }
            } catch (NoSuchMethodException e) {
                // Result is already the unwrapped value (inline class behavior)
                // If it's not a Throwable, there's no error
                return null;
            }
        } catch (Exception e) {
            log.trace("Could not extract tool error: {}", e.getMessage());
        }
        return null;
    }

    // ==================== Planning ====================

    private void onReadyToPlan(AgentProcessReadyToPlanEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String plannerType = process.getProcessOptions().getPlannerType().name();

        SpanContext parentCtx = activeSpans.get("agent:" + runId);
        Context parentContext = parentCtx != null ? Context.current().with(parentCtx.span) : Context.current();

        // Update agent span with input if not already captured
        if (parentCtx != null && !inputSnapshots.containsKey("agent:" + runId)) {
            String input = getBlackboardSnapshot(process);
            if (!input.isEmpty()) {
                parentCtx.span.setAttribute("input.value", truncate(input));
                inputSnapshots.put("agent:" + runId, input);
            }
        }

        Span span = tracer.spanBuilder("planning:ready")
                .setParent(parentContext)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("name", "planning:ready")
                .setAttribute("embabel.event.type", "planning_ready")
                .setAttribute("embabel.agent.run_id", runId)
                .setAttribute("embabel.plan.planner_type", plannerType)
                .startSpan();

        if (event.getWorldState() != null) {
            span.setAttribute("input.value", truncate(event.getWorldState().infoString(true, 0)));
        }

        span.setStatus(StatusCode.OK);
        span.end();

        log.debug("Recorded planning ready event (runId: {})", runId);
    }

    private void onPlanFormulated(AgentProcessPlanFormulatedEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        Plan plan = event.getPlan();

        int iteration = planIterations.compute(runId, (k, v) -> v == null ? 1 : v + 1);
        boolean isReplanning = iteration > 1;

        String spanName = isReplanning ? "planning:replanning" : "planning:formulated";
        String displayName = isReplanning ? "Replanning (iteration " + iteration + ")" : "Plan Formulated";

        SpanContext parentCtx = activeSpans.get("agent:" + runId);
        Context parentContext = parentCtx != null ? Context.current().with(parentCtx.span) : Context.current();

        Span span = tracer.spanBuilder(spanName)
                .setParent(parentContext)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("name", spanName)
                .setAttribute("gen_ai.operation.name", isReplanning ? "replanning" : "planning")
                .setAttribute("embabel.event.type", isReplanning ? "replanning" : "plan_formulated")
                .setAttribute("embabel.agent.run_id", runId)
                .setAttribute("embabel.plan.iteration", iteration)
                .setAttribute("embabel.plan.planner_type", process.getProcessOptions().getPlannerType().name())
                .startSpan();

        if (plan != null) {
            span.setAttribute("embabel.plan.actions_count", plan.getActions().size());
            span.setAttribute("output.value", truncate(formatPlanSteps(plan)));
            span.setAttribute("embabel.plan.actions", truncate(formatPlanSteps(plan)));
            if (plan.getGoal() != null) {
                span.setAttribute("embabel.plan.goal", plan.getGoal().getName());
            }
        }

        if (event.getWorldState() != null) {
            span.setAttribute("input.value", truncate(event.getWorldState().infoString(true, 0)));
        }

        span.setStatus(StatusCode.OK);
        span.end();

        log.debug("Recorded plan formulated event (iteration: {}, runId: {})", iteration, runId);
    }

    // ==================== State Transitions ====================

    private void onStateTransition(StateTransitionEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        Object newState = event.getNewState();

        String stateName = newState != null ? newState.getClass().getSimpleName() : "Unknown";

        SpanContext parentCtx = activeSpans.get("agent:" + runId);
        Context parentContext = parentCtx != null ? Context.current().with(parentCtx.span) : Context.current();

        Span span = tracer.spanBuilder("state:" + stateName)
                .setParent(parentContext)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("name", "state:" + stateName)
                .setAttribute("embabel.event.type", "state_transition")
                .setAttribute("embabel.agent.run_id", runId)
                .setAttribute("embabel.state.to", stateName)
                .setAttribute("embabel.state.blackboard_cleared", true)
                .startSpan();

        if (newState != null) {
            span.setAttribute("input.value", truncate(newState.toString()));
            span.setAttribute("embabel.state.value", truncate(newState.toString()));
        }

        span.setStatus(StatusCode.OK);
        span.end();

        log.debug("Recorded state transition to: {} (runId: {})", stateName, runId);
    }

    // ==================== Lifecycle States ====================

    private void onLifecycleState(AbstractAgentProcessEvent event, String state) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();

        SpanContext parentCtx = activeSpans.get("agent:" + runId);
        Context parentContext = parentCtx != null ? Context.current().with(parentCtx.span) : Context.current();

        Span span = tracer.spanBuilder("lifecycle:" + state.toLowerCase())
                .setParent(parentContext)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("name", "lifecycle:" + state.toLowerCase())
                .setAttribute("embabel.event.type", "lifecycle_" + state.toLowerCase())
                .setAttribute("embabel.agent.run_id", runId)
                .setAttribute("embabel.lifecycle.state", state)
                .startSpan();

        String snapshot = getBlackboardSnapshot(process);
        if (!snapshot.isEmpty()) {
            span.setAttribute("input.value", truncate(snapshot));
        }

        span.setStatus(StatusCode.OK);
        span.end();

        log.debug("Recorded lifecycle state: {} (runId: {})", state, runId);
    }

    // ==================== Object Binding ====================

    private void onObjectAdded(ObjectAddedEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        Object value = event.getValue();

        String objectType = value != null ? value.getClass().getSimpleName() : "Unknown";

        SpanContext parentCtx = findParentSpan(runId);
        Context parentContext = parentCtx != null ? Context.current().with(parentCtx.span) : Context.current();

        Span span = tracer.spanBuilder("object:added")
                .setParent(parentContext)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("name", "object:added:" + objectType)
                .setAttribute("embabel.event.type", "object_added")
                .setAttribute("embabel.agent.run_id", runId)
                .setAttribute("embabel.object.type", objectType)
                .startSpan();

        if (value != null) {
            span.setAttribute("input.value", truncate(value.toString()));
            span.setAttribute("embabel.object.value", truncate(value.toString()));
        }

        span.setStatus(StatusCode.OK);
        span.end();

        log.debug("Recorded object added: {} (runId: {})", objectType, runId);
    }

    private void onObjectBound(ObjectBoundEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String name = event.getName();
        Object value = event.getValue();

        String objectType = value != null ? value.getClass().getSimpleName() : "Unknown";

        SpanContext parentCtx = findParentSpan(runId);
        Context parentContext = parentCtx != null ? Context.current().with(parentCtx.span) : Context.current();

        Span span = tracer.spanBuilder("object:bound")
                .setParent(parentContext)
                .setSpanKind(SpanKind.INTERNAL)
                .setAttribute("name", "object:bound:" + name)
                .setAttribute("embabel.event.type", "object_bound")
                .setAttribute("embabel.agent.run_id", runId)
                .setAttribute("embabel.object.name", name)
                .setAttribute("embabel.object.type", objectType)
                .startSpan();

        if (value != null) {
            span.setAttribute("input.value", truncate(value.toString()));
            span.setAttribute("embabel.object.value", truncate(value.toString()));
        }

        span.setStatus(StatusCode.OK);
        span.end();

        log.debug("Recorded object bound: {} as {} (runId: {})", objectType, name, runId);
    }

    // ==================== Utility Methods ====================

    private SpanContext findParentSpan(String runId) {
        // First try to find an active action span
        for (String key : activeSpans.keySet()) {
            if (key.startsWith("action:" + runId)) {
                return activeSpans.get(key);
            }
        }
        // Fall back to agent span
        return activeSpans.get("agent:" + runId);
    }

    private String extractGoalName(AgentProcess process) {
        if (process.getGoal() != null) {
            return process.getGoal().getName();
        } else if (!process.getAgent().getGoals().isEmpty()) {
            return process.getAgent().getGoals().iterator().next().getName();
        }
        return "unknown";
    }

    private String getBlackboardSnapshot(AgentProcess process) {
        var objects = process.getBlackboard().getObjects();
        if (objects == null || objects.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (Object obj : objects) {
            if (obj != null) {
                if (sb.length() > 0) sb.append("\n---\n");
                sb.append(obj.getClass().getSimpleName()).append(": ");
                sb.append(obj.toString());
            }
        }
        return sb.toString();
    }

    /**
     * Extracts the declared inputs for an action from the blackboard.
     * Uses action.getInputs() to get the IoBinding declarations, then resolves
     * each binding from the blackboard using getValue().
     *
     * @param action the action being executed
     * @param process the agent process containing the blackboard
     * @return formatted string of action inputs, or empty string if no inputs
     */
    private String getActionInputs(Action action, AgentProcess process) {
        var inputs = action.getInputs();
        if (inputs == null || inputs.isEmpty()) {
            return "";
        }

        Blackboard blackboard = process.getBlackboard();
        StringBuilder sb = new StringBuilder();

        for (IoBinding input : inputs) {
            // IoBinding is a Kotlin value class - parse the raw value "name:type"
            String bindingValue = input.getValue();
            String name;
            String type;
            if (bindingValue.contains(":")) {
                String[] parts = bindingValue.split(":", 2);
                name = parts[0];
                type = parts[1];
            } else {
                name = "it"; // DEFAULT_BINDING
                type = bindingValue;
            }

            Object value = blackboard.getValue(name, type, process.getAgent());

            if (value != null) {
                if (sb.length() > 0) sb.append("\n---\n");
                sb.append(name).append(" (").append(type).append("): ");
                sb.append(value.toString());
            }
        }
        return sb.toString();
    }

    private String formatPlanSteps(Plan plan) {
        if (plan == null || plan.getActions() == null || plan.getActions().isEmpty()) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (var action : plan.getActions()) {
            if (sb.length() > 0) sb.append("\n");
            sb.append(index++).append(". ").append(action.getName());
        }
        if (plan.getGoal() != null) {
            sb.append("\n-> Goal: ").append(plan.getGoal().getName());
        }
        return sb.toString();
    }

    private String truncate(String value) {
        if (value == null) return "";
        int maxLength = properties.getMaxAttributeLength();
        return value.length() > maxLength ? value.substring(0, maxLength) + "..." : value;
    }
}
