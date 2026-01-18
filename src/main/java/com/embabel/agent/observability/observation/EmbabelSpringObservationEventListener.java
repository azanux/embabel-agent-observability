package com.embabel.agent.observability.observation;

import com.embabel.agent.api.event.*;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.ToolGroupMetadata;
import com.embabel.agent.observability.ObservabilityProperties;
import com.embabel.plan.Plan;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Event listener that traces Embabel Agent events using Micrometer Tracing API.
 *
 * <p>This implementation uses Micrometer's {@link Tracer} directly instead of the
 * Observation API. This ensures proper context propagation because:
 * <ul>
 *   <li>{@code tracer.withSpan(span)} puts the span in thread-local context</li>
 *   <li>Spring AI uses {@code tracer.currentSpan()} to find the parent</li>
 *   <li>The bridge to OpenTelemetry is handled by the Tracer implementation</li>
 * </ul>
 *
 * <p>Using the Tracer directly is more reliable than the Observation API because
 * it doesn't depend on the {@code DefaultTracingObservationHandler} being properly
 * registered and configured.
 *
 * @author Quantpulsar 2025-2026
 * @see ObservabilityProperties
 */
public class EmbabelSpringObservationEventListener implements AgenticEventListener {

    private static final Logger log = LoggerFactory.getLogger(EmbabelSpringObservationEventListener.class);

    // Micrometer tracer for creating spans
    private final Tracer tracer;
    // Configuration properties for observability
    private final ObservabilityProperties properties;

    // Stores active spans with their scopes for proper parent-child hierarchy
    private final Map<String, SpanContext> activeSpans = new ConcurrentHashMap<>();
    // Caches input data for spans to compute deltas
    private final Map<String, String> inputSnapshots = new ConcurrentHashMap<>();
    // Tracks replanning iterations per agent run
    private final Map<String, Integer> planIterations = new ConcurrentHashMap<>();

    /**
     * Holds both the Span and its Scope together.
     * The Scope MUST be closed BEFORE the Span ends to properly restore the previous context.
     */
    private record SpanContext(Span span, Tracer.SpanInScope scope) {}

    /**
     * Creates a new Spring Observation event listener.
     *
     * @param tracer the Micrometer Tracer
     * @param properties observability configuration properties
     */
    public EmbabelSpringObservationEventListener(
            Tracer tracer,
            ObservabilityProperties properties) {
        this.tracer = tracer;
        this.properties = properties;
        log.info("EmbabelSpringObservationEventListener initialized with Micrometer Tracer");
    }

    /**
     * Checks if tracing is available.
     */
    private boolean isTracingEnabled() {
        return tracer != null;
    }

    /**
     * Main event dispatcher - routes events to appropriate handlers based on type.
     */
    @Override
    public void onProcessEvent(@NotNull AgentProcessEvent event) {
        if (!isTracingEnabled()) {
            return;
        }
        // Route each event type to its specific handler
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

    /**
     * Handles agent creation - creates root or child span based on hierarchy.
     */
    private void onAgentProcessCreation(AgentProcessCreationEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String agentName = process.getAgent().getName();
        String parentId = process.getParentId();
        boolean isSubagent = parentId != null && !parentId.isEmpty();

        String goalName = extractGoalName(process);
        String plannerType = process.getProcessOptions().getPlannerType().name();
        String input = getBlackboardSnapshot(process);

        // Determine parent span for hierarchy
        Span parentSpan = null;
        if (isSubagent) {
            SpanContext parentCtx = activeSpans.get("agent:" + parentId);
            if (parentCtx != null) {
                parentSpan = parentCtx.span;
            }
        }

        // Create span with proper parent hierarchy
        Span span;
        if (isSubagent && parentSpan != null) {
            // Subagent with known parent - create as child
            span = tracer.nextSpan(parentSpan).name(agentName);
        } else if (isSubagent) {
            // Subagent without known parent - use current context as parent
            span = tracer.nextSpan().name(agentName);
        } else {
            // Root agent - create a NEW trace by temporarily clearing the context
            // This ensures each user request gets its own trace ID
            span = createRootSpan(agentName);
        }

        span.tag("gen_ai.operation.name", "agent");
        span.tag("gen_ai.conversation.id", runId);

        // Add tags
        span.tag("embabel.agent.name", agentName);
        span.tag("embabel.agent.run_id", runId);
        span.tag("embabel.agent.goal", goalName);
        span.tag("embabel.agent.is_subagent", String.valueOf(isSubagent));
        span.tag("embabel.agent.parent_id", parentId != null ? parentId : "");
        span.tag("embabel.agent.planner_type", plannerType);
        span.tag("embabel.event.type", "agent_process");

        if (!input.isEmpty()) {
            span.tag("input.value", truncate(input));
            inputSnapshots.put("agent:" + runId, input);
        }

        // Initialize plan iteration counter
        planIterations.put(runId, 0);

        // Start the span and put it in scope
        // CRITICAL: withSpan() puts the span in thread-local context
        // This allows Spring AI to find it via tracer.currentSpan()
        span.start();
        Tracer.SpanInScope scope = tracer.withSpan(span);
        activeSpans.put("agent:" + runId, new SpanContext(span, scope));

        log.debug("Started span for agent: {} (runId: {})", agentName, runId);
    }

    /**
     * Handles successful agent completion - closes span with output tags.
     */
    private void onAgentProcessCompleted(AgentProcessCompletedEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String key = "agent:" + runId;

        SpanContext ctx = activeSpans.remove(key);
        inputSnapshots.remove(key);
        planIterations.remove(runId);

        if (ctx != null) {
            ctx.span.tag("embabel.agent.status", "completed");

            String output = getBlackboardSnapshot(process);
            if (!output.isEmpty()) {
                ctx.span.tag("output.value", truncate(output));
                ctx.span.tag("embabel.agent.output", truncate(output));
            }

            Object lastResult = process.getBlackboard().lastResult();
            if (lastResult != null) {
                ctx.span.tag("embabel.agent.result", truncate(lastResult.toString()));
            }

            // CRITICAL: Close scope BEFORE ending span to restore previous context
            ctx.scope.close();
            ctx.span.end();

            log.debug("Completed span for agent runId: {}", runId);
        }
    }

    /**
     * Handles agent failure - marks span as error with failure info.
     */
    private void onAgentProcessFailed(AgentProcessFailedEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String key = "agent:" + runId;

        SpanContext ctx = activeSpans.remove(key);
        inputSnapshots.remove(key);
        planIterations.remove(runId);

        if (ctx != null) {
            ctx.span.tag("embabel.agent.status", "failed");

            Object failureInfo = process.getFailureInfo();
            if (failureInfo != null) {
                ctx.span.error(new RuntimeException(truncate(failureInfo.toString())));
            }

            ctx.scope.close();
            ctx.span.end();

            log.debug("Failed span for agent runId: {}", runId);
        }
    }

    // ==================== Actions ====================

    /**
     * Starts a span for action execution - child of agent span.
     */
    private void onActionStart(ActionExecutionStartEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String actionName = event.getAction().getName();
        String shortName = event.getAction().shortName();
        String input = getBlackboardSnapshot(process);

        // Get parent span - prefer agent span
        SpanContext parentCtx = activeSpans.get("agent:" + runId);

        // Create child span
        Span span;
        if (parentCtx != null) {
            span = tracer.nextSpan(parentCtx.span).name(shortName);
        } else {
            span = tracer.nextSpan().name(shortName);
        }

        // Add tags
        span.tag("embabel.action.name", actionName);
        span.tag("embabel.action.short_name", shortName);
        span.tag("embabel.action.run_id", runId);
        span.tag("embabel.action.description", event.getAction().getDescription());
        span.tag("embabel.event.type", "action");
        span.tag("gen_ai.operation.name", "execute_action");

        String key = "action:" + runId + ":" + actionName;
        if (!input.isEmpty()) {
            span.tag("input.value", truncate(input));
            inputSnapshots.put(key, input);
        }

        // CRITICAL: Start span and put in scope so Spring AI LLM calls become children
        span.start();
        Tracer.SpanInScope scope = tracer.withSpan(span);
        activeSpans.put(key, new SpanContext(span, scope));

        log.debug("Started span for action: {} (runId: {})", shortName, runId);
    }

    /**
     * Completes action span with status, duration and result tags.
     */
    private void onActionResult(ActionExecutionResultEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String actionName = event.getAction().getName();
        String key = "action:" + runId + ":" + actionName;

        SpanContext ctx = activeSpans.remove(key);
        inputSnapshots.remove(key);

        if (ctx != null) {
            String statusName = event.getActionStatus().getStatus().name();
            ctx.span.tag("embabel.action.status", statusName);
            ctx.span.tag("embabel.action.duration_ms", String.valueOf(event.getRunningTime().toMillis()));

            String output = getBlackboardSnapshot(process);
            if (!output.isEmpty()) {
                ctx.span.tag("output.value", truncate(output));
            }

            Object lastResult = process.getBlackboard().lastResult();
            if (lastResult != null) {
                ctx.span.tag("embabel.action.result", truncate(lastResult.toString()));
            }

            ctx.scope.close();
            ctx.span.end();

            log.debug("Completed span for action: {} (runId: {})", actionName, runId);
        }
    }

    // ==================== Goals ====================

    /**
     * Records goal achievement as instant span (starts and ends immediately).
     */
    private void onGoalAchieved(GoalAchievedEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String goalName = event.getGoal().getName();

        String shortGoalName = goalName.contains(".")
                ? goalName.substring(goalName.lastIndexOf('.') + 1)
                : goalName;

        // Get parent span
        SpanContext parentCtx = activeSpans.get("agent:" + runId);

        Span span;
        if (parentCtx != null) {
            span = tracer.nextSpan(parentCtx.span).name("goal:" + shortGoalName);
        } else {
            span = tracer.nextSpan().name("goal:" + shortGoalName);
        }

        span.tag("embabel.goal.name", goalName);
        span.tag("embabel.goal.short_name", shortGoalName);
        span.tag("embabel.event.type", "goal_achieved");

        String snapshot = getBlackboardSnapshot(process);
        if (!snapshot.isEmpty()) {
            span.tag("input.value", truncate(snapshot));
        }

        Object lastResult = process.getBlackboard().lastResult();
        if (lastResult != null) {
            span.tag("output.value", truncate(lastResult.toString()));
        }

        // Instant span - start and end immediately
        span.start();
        span.end();

        log.debug("Recorded goal achieved: {} (runId: {})", shortGoalName, runId);
    }

    // ==================== Tools ====================

    /**
     * Starts span for tool invocation - captures tool name, input, and metadata.
     * Uses current tracer span as parent to integrate with Spring AI ChatClient.
     */
    private void onToolCallRequest(ToolCallRequestEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String toolName = event.getTool();

        // Use current tracer span as parent (could be Spring AI ChatClient span)
        // This ensures tool calls are nested under LLM calls
        Span currentSpan = tracer.currentSpan();

        Span span;
        if (currentSpan != null) {
            // Parent is the current span (ChatClient or Action)
            span = tracer.nextSpan(currentSpan).name("tool:" + toolName);
        } else {
            // Fallback to Embabel hierarchy
            SpanContext parentCtx = findParentSpan(runId);
            if (parentCtx != null) {
                span = tracer.nextSpan(parentCtx.span).name("tool:" + toolName);
            } else {
                span = tracer.nextSpan().name("tool:" + toolName);
            }
        }

        // OpenTelemetry GenAI semantic conventions for tool execution
        span.tag("gen_ai.operation.name", "execute_tool");
        span.tag("gen_ai.tool.name", toolName);
        span.tag("gen_ai.tool.type", "function");

        span.tag("embabel.tool.name", toolName);
        span.tag("embabel.event.type", "tool_call");

        // Add correlation ID for tracing tool calls across systems
        String correlationId = event.getCorrelationId();
        if (correlationId != null && !"-".equals(correlationId)) {
            span.tag("embabel.tool.correlation_id", correlationId);
        }

        // Add tool group metadata if available (description, role, etc.)
        ToolGroupMetadata metadata = event.getToolGroupMetadata();
        if (metadata != null) {
            String description = metadata.getDescription();
            if (description != null && !description.isEmpty()) {
                span.tag("gen_ai.tool.description", truncate(description));
            }
            String groupName = metadata.getName();
            if (groupName != null) {
                span.tag("embabel.tool.group.name", groupName);
            }
            String role = metadata.getRole();
            if (role != null) {
                span.tag("embabel.tool.group.role", role);
            }
        }

        if (event.getToolInput() != null) {
            span.tag("input.value", truncate(event.getToolInput()));
            span.tag("gen_ai.tool.call.arguments", truncate(event.getToolInput()));
        }

        span.start();
        Tracer.SpanInScope scope = tracer.withSpan(span);
        activeSpans.put("tool:" + runId + ":" + toolName, new SpanContext(span, scope));

        log.debug("Started span for tool: {} (runId: {}, correlationId: {})",
                toolName, runId, correlationId);
    }

    /**
     * Completes tool span with duration and output tags.
     * Captures tool output from event.getResult() using reflection (Kotlin mangled method).
     */
    private void onToolCallResponse(ToolCallResponseEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String toolName = event.getRequest().getTool();
        String key = "tool:" + runId + ":" + toolName;

        SpanContext ctx = activeSpans.remove(key);

        if (ctx != null) {
            ctx.span.tag("embabel.tool.duration_ms", String.valueOf(event.getRunningTime().toMillis()));

            // Extract tool result using reflection (Kotlin Result has mangled method names)
            Object toolResult = extractToolResult(event);
            if (toolResult != null) {
                String truncatedResult = truncate(toolResult.toString());
                ctx.span.tag("output.value", truncatedResult);
                ctx.span.tag("gen_ai.tool.call.result", truncatedResult);
                ctx.span.tag("embabel.tool.status", "success");
            } else {
                // Check if there was an error
                Throwable error = extractToolError(event);
                if (error != null) {
                    ctx.span.tag("embabel.tool.status", "error");
                    ctx.span.tag("embabel.tool.error.type", error.getClass().getSimpleName());
                    ctx.span.tag("embabel.tool.error.message", truncate(error.getMessage()));
                    ctx.span.error(error);
                } else {
                    // No result and no error - mark as success
                    ctx.span.tag("embabel.tool.status", "success");
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

    /**
     * Records planning ready event - agent is ready to formulate a plan.
     */
    private void onReadyToPlan(AgentProcessReadyToPlanEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String plannerType = process.getProcessOptions().getPlannerType().name();

        SpanContext parentCtx = activeSpans.get("agent:" + runId);

        Span span;
        if (parentCtx != null) {
            span = tracer.nextSpan(parentCtx.span).name("planning:ready");
        } else {
            span = tracer.nextSpan().name("planning:ready");
        }

        span.tag("embabel.event.type", "planning_ready");
        span.tag("embabel.agent.run_id", runId);
        span.tag("embabel.plan.planner_type", plannerType);

        if (event.getWorldState() != null) {
            span.tag("input.value", truncate(event.getWorldState().infoString(true, 0)));
        }

        span.start();
        span.end();

        log.debug("Recorded planning ready event (runId: {})", runId);
    }

    /**
     * Records plan formulation - tracks initial plans and replanning iterations.
     */
    private void onPlanFormulated(AgentProcessPlanFormulatedEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        Plan plan = event.getPlan();
        // Increment iteration counter to detect replanning
        int iteration = planIterations.compute(runId, (k, v) -> v == null ? 1 : v + 1);
        boolean isReplanning = iteration > 1;

        String spanName = isReplanning ? "planning:replanning" : "planning:formulated";
        String displayName = isReplanning ? "Replanning (iteration " + iteration + ")" : "Plan Formulated";

        SpanContext parentCtx = activeSpans.get("agent:" + runId);

        Span span;
        if (parentCtx != null) {
            span = tracer.nextSpan(parentCtx.span).name(spanName);
        } else {
            span = tracer.nextSpan().name(spanName);
        }

        span.tag("embabel.event.type", isReplanning ? "replanning" : "plan_formulated");
        span.tag("gen_ai.operation.name", isReplanning ? "replanning" : "planning");


        span.tag("embabel.agent.run_id", runId);
        span.tag("embabel.plan.is_replanning", String.valueOf(isReplanning));
        span.tag("embabel.plan.iteration", String.valueOf(iteration));
        span.tag("embabel.plan.planner_type", process.getProcessOptions().getPlannerType().name());

        if (plan != null) {
            span.tag("embabel.plan.actions_count", String.valueOf(plan.getActions().size()));
            span.tag("output.value", truncate(formatPlanSteps(plan)));
            if (plan.getGoal() != null) {
                span.tag("embabel.plan.goal", plan.getGoal().getName());
            }
        }

        if (event.getWorldState() != null) {
            span.tag("input.value", truncate(event.getWorldState().infoString(true, 0)));
        }

        span.start();
        span.end();

        log.debug("Recorded plan formulated event (iteration: {}, runId: {})", iteration, runId);
    }

    // ==================== State Transitions ====================

    /**
     * Records blackboard state transitions as instant spans.
     */
    private void onStateTransition(StateTransitionEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        Object newState = event.getNewState();

        String stateName = newState != null ? newState.getClass().getSimpleName() : "Unknown";

        SpanContext parentCtx = activeSpans.get("agent:" + runId);

        Span span;
        if (parentCtx != null) {
            span = tracer.nextSpan(parentCtx.span).name("state:" + stateName);
        } else {
            span = tracer.nextSpan().name("state:" + stateName);
        }

        span.tag("embabel.event.type", "state_transition");
        span.tag("embabel.agent.run_id", runId);
        span.tag("embabel.state.to", stateName);

        if (newState != null) {
            span.tag("input.value", truncate(newState.toString()));
        }

        span.start();
        span.end();

        log.debug("Recorded state transition to: {} (runId: {})", stateName, runId);
    }

    // ==================== Lifecycle States ====================

    /**
     * Records lifecycle events (WAITING, PAUSED, STUCK) as instant spans.
     */
    private void onLifecycleState(AbstractAgentProcessEvent event, String state) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();

        SpanContext parentCtx = activeSpans.get("agent:" + runId);

        Span span;
        if (parentCtx != null) {
            span = tracer.nextSpan(parentCtx.span).name("lifecycle:" + state.toLowerCase());
        } else {
            span = tracer.nextSpan().name("lifecycle:" + state.toLowerCase());
        }

        span.tag("embabel.event.type", "lifecycle_" + state.toLowerCase());
        span.tag("embabel.agent.run_id", runId);
        span.tag("embabel.lifecycle.state", state);

        String snapshot = getBlackboardSnapshot(process);
        if (!snapshot.isEmpty()) {
            span.tag("input.value", truncate(snapshot));
        }

        span.start();
        span.end();

        log.debug("Recorded lifecycle state: {} (runId: {})", state, runId);
    }

    // ==================== Object Binding ====================

    /**
     * Records when objects are added to the blackboard.
     */
    private void onObjectAdded(ObjectAddedEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        Object value = event.getValue();

        String objectType = value != null ? value.getClass().getSimpleName() : "Unknown";

        SpanContext parentCtx = findParentSpan(runId);

        Span span;
        if (parentCtx != null) {
            span = tracer.nextSpan(parentCtx.span).name("object:added");
        } else {
            span = tracer.nextSpan().name("object:added");
        }

        span.tag("embabel.event.type", "object_added");
        span.tag("embabel.agent.run_id", runId);
        span.tag("embabel.object.type", objectType);

        if (value != null) {
            span.tag("input.value", truncate(value.toString()));
        }

        span.start();
        span.end();

        log.debug("Recorded object added: {} (runId: {})", objectType, runId);
    }

    /**
     * Records when objects are bound to named variables in the blackboard.
     */
    private void onObjectBound(ObjectBoundEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String name = event.getName();
        Object value = event.getValue();

        String objectType = value != null ? value.getClass().getSimpleName() : "Unknown";

        SpanContext parentCtx = findParentSpan(runId);

        Span span;
        if (parentCtx != null) {
            span = tracer.nextSpan(parentCtx.span).name("object:bound");
        } else {
            span = tracer.nextSpan().name("object:bound");
        }

        span.tag("embabel.event.type", "object_bound");
        span.tag("embabel.agent.run_id", runId);
        span.tag("embabel.object.name", name);
        span.tag("embabel.object.type", objectType);

        if (value != null) {
            span.tag("input.value", truncate(value.toString()));
        }

        span.start();
        span.end();

        log.debug("Recorded object bound: {} as {} (runId: {})", objectType, name, runId);
    }

    // ==================== Utility Methods ====================

    /**
     * Creates a root span for a new trace.
     *
     * <p>This method temporarily clears the current span context using
     * {@code tracer.withSpan(null)} to ensure the new span has no parent.
     * This is essential for creating a new trace for each user request
     * instead of nesting all requests under the first one.
     *
     * @param name the span name
     * @return a new root span with no parent
     */
    private Span createRootSpan(String name) {
        // Temporarily clear the current span context by setting it to null
        // This ensures nextSpan() creates a span without a parent
        try (Tracer.SpanInScope ignored = tracer.withSpan(null)) {
            return tracer.nextSpan().name(name);
        }
        // After the try block, the previous context is restored,
        // but the span's parent is already determined (none)
    }

    /**
     * Finds best parent span - prefers action span over agent span.
     */
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

    /**
     * Extracts goal name from process or agent configuration.
     */
    private String extractGoalName(AgentProcess process) {
        if (process.getGoal() != null) {
            return process.getGoal().getName();
        } else if (!process.getAgent().getGoals().isEmpty()) {
            return process.getAgent().getGoals().iterator().next().getName();
        }
        return "unknown";
    }

    /**
     * Creates a string representation of all objects on the blackboard.
     */
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
     * Formats plan actions as numbered list with goal.
     */
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

    /**
     * Truncates string to configured max length for span attributes.
     */
    private String truncate(String value) {
        if (value == null) return "";
        int maxLength = properties.getMaxAttributeLength();
        return value.length() > maxLength ? value.substring(0, maxLength) + "..." : value;
    }
}
