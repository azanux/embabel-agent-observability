package com.embabel.agent.observability.observation;

import com.embabel.agent.api.event.*;
import com.embabel.agent.core.Action;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Blackboard;
import com.embabel.agent.core.IoBinding;
import com.embabel.agent.core.ToolGroupMetadata;
import com.embabel.agent.observability.ObservabilityProperties;
import com.embabel.plan.Plan;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Traces Embabel Agent events via Spring Observation API.
 * Provides traces (custom handler) and metrics (automatic).
 *
 * @author Quantpulsar 2025-2026
 */
public class EmbabelFullObservationEventListener implements AgenticEventListener {

    private static final Logger log = LoggerFactory.getLogger(EmbabelFullObservationEventListener.class);

    private final ObservationRegistry observationRegistry;
    private final ObservabilityProperties properties;

    // Active observations keyed by type:runId:name
    private final Map<String, ObservationContext> activeObservations = new ConcurrentHashMap<>();
    private final Map<String, String> inputSnapshots = new ConcurrentHashMap<>();
    private final Map<String, Integer> planIterations = new ConcurrentHashMap<>();

    /** Holds Observation and its Scope. */
    private record ObservationContext(Observation observation, Observation.Scope scope) {}

    /** Creates the listener with required dependencies. */
    public EmbabelFullObservationEventListener(
            ObservationRegistry observationRegistry,
            ObservabilityProperties properties) {
        this.observationRegistry = observationRegistry;
        this.properties = properties;
        log.info("EmbabelFullObservationEventListener initialized with Spring Observation API");
    }

    /** Routes incoming events to the appropriate handler methods. */
    @Override
    public void onProcessEvent(@NotNull AgentProcessEvent event) {
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
            default -> {}
        }
    }

    // --- Agent Lifecycle ---

    /** Starts a new observation span when an agent process is created. */
    private void onAgentProcessCreation(AgentProcessCreationEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String agentName = process.getAgent().getName();
        String parentId = process.getParentId();
        boolean isSubagent = parentId != null && !parentId.isEmpty();

        String goalName = extractGoalName(process);
        String plannerType = process.getProcessOptions().getPlannerType().name();
        String input = getBlackboardSnapshot(process);

        // Root or subagent context
        EmbabelObservationContext context = isSubagent
                ? EmbabelObservationContext.subAgent(runId, agentName, parentId)
                : EmbabelObservationContext.rootAgent(runId, agentName);

        // Create observation
        Observation observation = Observation.createNotStarted(agentName, () -> context, observationRegistry);

        // OpenTelemetry GenAI semantic conventions
        observation.lowCardinalityKeyValue("gen_ai.operation.name", "agent");
        observation.highCardinalityKeyValue("gen_ai.conversation.id", runId);

        // Low cardinality (metrics)
        observation.lowCardinalityKeyValue("embabel.agent.name", agentName);
        observation.lowCardinalityKeyValue("embabel.agent.is_subagent", String.valueOf(isSubagent));
        observation.lowCardinalityKeyValue("embabel.agent.planner_type", plannerType);
        observation.lowCardinalityKeyValue("embabel.event.type", "agent_process");

        // High cardinality (traces)
        observation.highCardinalityKeyValue("embabel.agent.run_id", runId);
        observation.highCardinalityKeyValue("embabel.agent.goal", goalName);
        observation.highCardinalityKeyValue("embabel.agent.parent_id", parentId != null ? parentId : "");

        if (!input.isEmpty()) {
            observation.highCardinalityKeyValue("input.value", truncate(input));
            inputSnapshots.put("agent:" + runId, input);
        }

        // Init plan counter
        planIterations.put(runId, 0);

        // Start and open scope
        observation.start();
        Observation.Scope scope = observation.openScope();

        activeObservations.put("agent:" + runId, new ObservationContext(observation, scope));
        log.debug("Started observation for agent: {} (runId: {})", agentName, runId);
    }

    /** Closes the observation span when agent completes successfully. */
    private void onAgentProcessCompleted(AgentProcessCompletedEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String key = "agent:" + runId;

        ObservationContext ctx = activeObservations.remove(key);
        inputSnapshots.remove(key);
        planIterations.remove(runId);

        if (ctx != null) {
            ctx.observation.lowCardinalityKeyValue("embabel.agent.status", "completed");

            String output = getBlackboardSnapshot(process);
            if (!output.isEmpty()) {
                ctx.observation.highCardinalityKeyValue("output.value", truncate(output));
            }

            Object lastResult = process.getBlackboard().lastResult();
            if (lastResult != null) {
                ctx.observation.highCardinalityKeyValue("embabel.agent.result", truncate(lastResult.toString()));
            }

            // Close and stop
            ctx.scope.close();
            ctx.observation.stop();

            log.debug("Completed observation for agent runId: {}", runId);
        }
    }

    /** Closes the observation span with error status when agent fails. */
    private void onAgentProcessFailed(AgentProcessFailedEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String key = "agent:" + runId;

        ObservationContext ctx = activeObservations.remove(key);
        inputSnapshots.remove(key);
        planIterations.remove(runId);

        if (ctx != null) {
            ctx.observation.lowCardinalityKeyValue("embabel.agent.status", "failed");

            Object failureInfo = process.getFailureInfo();
            if (failureInfo != null) {
                ctx.observation.highCardinalityKeyValue("embabel.agent.error", truncate(failureInfo.toString()));
                ctx.observation.error(new RuntimeException(truncate(failureInfo.toString())));
            }

            ctx.scope.close();
            ctx.observation.stop();

            log.debug("Failed observation for agent runId: {}", runId);
        }
    }

    // --- Actions ---

    /** Starts a new observation span when an action begins execution. */
    private void onActionStart(ActionExecutionStartEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        Action action = event.getAction();
        String actionName = action.getName();
        String shortName = action.shortName();
        String input = getActionInputs(action, process);

        EmbabelObservationContext context = EmbabelObservationContext.action(runId, shortName);

        Observation observation = Observation.createNotStarted(shortName, () -> context, observationRegistry);

        // OpenTelemetry GenAI semantic convention
        observation.lowCardinalityKeyValue("gen_ai.operation.name", "execute_action");
        observation.lowCardinalityKeyValue("embabel.event.type", "action");

        observation.lowCardinalityKeyValue("embabel.action.short_name", shortName);
        observation.highCardinalityKeyValue("embabel.action.name", actionName);
        observation.highCardinalityKeyValue("embabel.action.run_id", runId);
        observation.highCardinalityKeyValue("embabel.action.description", event.getAction().getDescription());

        String key = "action:" + runId + ":" + actionName;
        if (!input.isEmpty()) {
            observation.highCardinalityKeyValue("input.value", truncate(input));
            inputSnapshots.put(key, input);
        }

        observation.start();
        Observation.Scope scope = observation.openScope();

        activeObservations.put(key, new ObservationContext(observation, scope));
        log.debug("Started observation for action: {} (runId: {})", shortName, runId);
    }

    /** Closes the action observation span with execution result. */
    private void onActionResult(ActionExecutionResultEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String actionName = event.getAction().getName();
        String key = "action:" + runId + ":" + actionName;

        ObservationContext ctx = activeObservations.remove(key);
        inputSnapshots.remove(key);

        if (ctx != null) {
            String statusName = event.getActionStatus().getStatus().name();
            ctx.observation.lowCardinalityKeyValue("embabel.action.status", statusName);
            ctx.observation.highCardinalityKeyValue("embabel.action.duration_ms",
                    String.valueOf(event.getRunningTime().toMillis()));

            String output = getBlackboardSnapshot(process);
            if (!output.isEmpty()) {
                ctx.observation.highCardinalityKeyValue("output.value", truncate(output));
            }

            Object lastResult = process.getBlackboard().lastResult();
            if (lastResult != null) {
                ctx.observation.highCardinalityKeyValue("embabel.action.result", truncate(lastResult.toString()));
            }

            ctx.scope.close();
            ctx.observation.stop();

            log.debug("Completed observation for action: {} (runId: {})", actionName, runId);
        }
    }

    // --- Goals ---

    /** Records an instant observation event when a goal is achieved. */
    private void onGoalAchieved(GoalAchievedEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String goalName = event.getGoal().getName();

        String shortGoalName = goalName.contains(".")
                ? goalName.substring(goalName.lastIndexOf('.') + 1)
                : goalName;

        EmbabelObservationContext context = EmbabelObservationContext.goal(runId, "goal:" + shortGoalName);

        Observation observation = Observation.createNotStarted("goal:" + shortGoalName, () -> context, observationRegistry);

        observation.lowCardinalityKeyValue("embabel.goal.short_name", shortGoalName);
        observation.highCardinalityKeyValue("embabel.goal.name", goalName);
        observation.highCardinalityKeyValue("embabel.event.type", "goal_achieved");

        String snapshot = getBlackboardSnapshot(process);
        if (!snapshot.isEmpty()) {
            observation.highCardinalityKeyValue("input.value", truncate(snapshot));
        }

        Object lastResult = process.getBlackboard().lastResult();
        if (lastResult != null) {
            observation.highCardinalityKeyValue("output.value", truncate(lastResult.toString()));
        }

        // Instant event
        observation.start();
        observation.stop();

        log.debug("Recorded goal achieved: {} (runId: {})", shortGoalName, runId);
    }

    // --- Tools ---

    /**
     * Starts a new observation span when a tool call is initiated.
     * Uses current observation as parent to integrate with Spring AI ChatClient.
     */
    private void onToolCallRequest(ToolCallRequestEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String toolName = event.getTool();

        // Get current observation (could be Spring AI ChatClient observation)
        // This ensures tool calls are nested under LLM calls
        Observation parentObservation = observationRegistry.getCurrentObservation();

        EmbabelObservationContext context = EmbabelObservationContext.toolCall(runId, "tool:" + toolName);

        Observation observation = Observation.createNotStarted("tool:" + toolName, () -> context, observationRegistry);

        // Set parent observation explicitly for proper hierarchy
        if (parentObservation != null) {
            observation.parentObservation(parentObservation);
        }

        // OpenTelemetry GenAI semantic conventions for tool execution
        observation.lowCardinalityKeyValue("gen_ai.operation.name", "execute_tool");
        observation.lowCardinalityKeyValue("gen_ai.tool.name", toolName);
        observation.lowCardinalityKeyValue("gen_ai.tool.type", "function");

        observation.lowCardinalityKeyValue("embabel.tool.name", toolName);
        observation.lowCardinalityKeyValue("embabel.event.type", "tool_call");

        // Add correlation ID for tracing tool calls across systems
        String correlationId = event.getCorrelationId();
        if (correlationId != null && !"-".equals(correlationId)) {
            observation.highCardinalityKeyValue("embabel.tool.correlation_id", correlationId);
        }

        // Add tool group metadata if available (description, role, etc.)
        ToolGroupMetadata metadata = event.getToolGroupMetadata();
        if (metadata != null) {
            String description = metadata.getDescription();
            if (description != null && !description.isEmpty()) {
                observation.highCardinalityKeyValue("gen_ai.tool.description", truncate(description));
            }
            String groupName = metadata.getName();
            if (groupName != null) {
                observation.lowCardinalityKeyValue("embabel.tool.group.name", groupName);
            }
            String role = metadata.getRole();
            if (role != null) {
                observation.lowCardinalityKeyValue("embabel.tool.group.role", role);
            }
        }

        if (event.getToolInput() != null) {
            String truncatedInput = truncate(event.getToolInput());
            observation.highCardinalityKeyValue("input.value", truncatedInput);
            observation.highCardinalityKeyValue("gen_ai.tool.call.arguments", truncatedInput);
        }

        observation.start();
        Observation.Scope scope = observation.openScope();

        activeObservations.put("tool:" + runId + ":" + toolName, new ObservationContext(observation, scope));
        log.debug("Started observation for tool: {} (runId: {}, correlationId: {}, parentObservation: {})",
                toolName, runId, correlationId,
                parentObservation != null ? parentObservation.getContext().getName() : "none");
    }

    /**
     * Closes the tool call observation span with response data.
     * Captures tool output from blackboard.lastResult().
     */
    private void onToolCallResponse(ToolCallResponseEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String toolName = event.getRequest().getTool();
        String key = "tool:" + runId + ":" + toolName;

        ObservationContext ctx = activeObservations.remove(key);

        if (ctx != null) {
            ctx.observation.highCardinalityKeyValue("embabel.tool.duration_ms",
                    String.valueOf(event.getRunningTime().toMillis()));

            // Extract tool result using reflection (Kotlin Result has mangled method names)
            Object toolResult = extractToolResult(event);
            if (toolResult != null) {
                String truncatedResult = truncate(toolResult.toString());
                ctx.observation.highCardinalityKeyValue("output.value", truncatedResult);
                ctx.observation.highCardinalityKeyValue("gen_ai.tool.call.result", truncatedResult);
                ctx.observation.lowCardinalityKeyValue("embabel.tool.status", "success");
            } else {
                // Check if there was an error
                Throwable error = extractToolError(event);
                if (error != null) {
                    ctx.observation.lowCardinalityKeyValue("embabel.tool.status", "error");
                    ctx.observation.highCardinalityKeyValue("embabel.tool.error.type", error.getClass().getSimpleName());
                    ctx.observation.highCardinalityKeyValue("embabel.tool.error.message", truncate(error.getMessage()));
                    ctx.observation.error(error);
                } else {
                    ctx.observation.lowCardinalityKeyValue("embabel.tool.status", "success");
                }
            }

            ctx.scope.close();
            ctx.observation.stop();

            log.debug("Completed observation for tool: {} (runId: {})", toolName, runId);
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

    // --- Planning ---

    /** Records an instant observation when the agent is ready to plan. */
    private void onReadyToPlan(AgentProcessReadyToPlanEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        String plannerType = process.getProcessOptions().getPlannerType().name();

        EmbabelObservationContext context = EmbabelObservationContext.planning(runId, "planning:ready");

        Observation observation = Observation.createNotStarted("planning:ready", () -> context, observationRegistry);

        // OpenTelemetry GenAI semantic convention
        observation.lowCardinalityKeyValue("gen_ai.operation.name", "planning");
        observation.lowCardinalityKeyValue("embabel.event.type", "planning_ready");

        observation.lowCardinalityKeyValue("embabel.plan.planner_type", plannerType);
        observation.highCardinalityKeyValue("embabel.agent.run_id", runId);

        if (event.getWorldState() != null) {
            observation.highCardinalityKeyValue("input.value", truncate(event.getWorldState().infoString(true, 0)));
        }

        observation.start();
        observation.stop();

        log.debug("Recorded planning ready event (runId: {})", runId);
    }

    /** Records an instant observation when a plan is formulated or replanned. */
    private void onPlanFormulated(AgentProcessPlanFormulatedEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        Plan plan = event.getPlan();

        int iteration = planIterations.compute(runId, (k, v) -> v == null ? 1 : v + 1);
        boolean isReplanning = iteration > 1;

        String spanName = isReplanning ? "planning:replanning" : "planning:formulated";

        EmbabelObservationContext context = EmbabelObservationContext.planning(runId, spanName);

        Observation observation = Observation.createNotStarted(spanName, () -> context, observationRegistry);

        // OpenTelemetry GenAI semantic convention
        observation.lowCardinalityKeyValue("gen_ai.operation.name", isReplanning ? "replanning" : "planning");
        observation.lowCardinalityKeyValue("embabel.event.type", isReplanning ? "replanning" : "plan_formulated");

        observation.lowCardinalityKeyValue("embabel.plan.is_replanning", String.valueOf(isReplanning));
        observation.lowCardinalityKeyValue("embabel.plan.planner_type", process.getProcessOptions().getPlannerType().name());
        observation.highCardinalityKeyValue("embabel.agent.run_id", runId);
        observation.highCardinalityKeyValue("embabel.plan.iteration", String.valueOf(iteration));

        if (plan != null) {
            observation.highCardinalityKeyValue("embabel.plan.actions_count", String.valueOf(plan.getActions().size()));
            observation.highCardinalityKeyValue("output.value", truncate(formatPlanSteps(plan)));
            if (plan.getGoal() != null) {
                observation.highCardinalityKeyValue("embabel.plan.goal", plan.getGoal().getName());
            }
        }

        if (event.getWorldState() != null) {
            observation.highCardinalityKeyValue("input.value", truncate(event.getWorldState().infoString(true, 0)));
        }

        observation.start();
        observation.stop();

        log.debug("Recorded plan formulated event (iteration: {}, runId: {})", iteration, runId);
    }

    // --- State Transitions ---

    /** Records an instant observation when the agent transitions to a new state. */
    private void onStateTransition(StateTransitionEvent event) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();
        Object newState = event.getNewState();

        String stateName = newState != null ? newState.getClass().getSimpleName() : "Unknown";

        EmbabelObservationContext context = EmbabelObservationContext.stateTransition(runId, "state:" + stateName);

        Observation observation = Observation.createNotStarted("state:" + stateName, () -> context, observationRegistry);

        observation.lowCardinalityKeyValue("embabel.state.to", stateName);
        observation.lowCardinalityKeyValue("embabel.event.type", "state_transition");
        observation.highCardinalityKeyValue("embabel.agent.run_id", runId);

        if (newState != null) {
            observation.highCardinalityKeyValue("input.value", truncate(newState.toString()));
        }

        observation.start();
        observation.stop();

        log.debug("Recorded state transition to: {} (runId: {})", stateName, runId);
    }

    // --- Lifecycle States ---

    /** Records an instant observation for lifecycle state changes (waiting, paused, stuck). */
    private void onLifecycleState(AbstractAgentProcessEvent event, String state) {
        AgentProcess process = event.getAgentProcess();
        String runId = process.getId();

        EmbabelObservationContext context = EmbabelObservationContext.lifecycle(runId, "lifecycle:" + state.toLowerCase());

        Observation observation = Observation.createNotStarted("lifecycle:" + state.toLowerCase(), () -> context, observationRegistry);

        observation.lowCardinalityKeyValue("embabel.lifecycle.state", state);
        observation.highCardinalityKeyValue("embabel.agent.run_id", runId);
        observation.highCardinalityKeyValue("embabel.event.type", "lifecycle_" + state.toLowerCase());

        String snapshot = getBlackboardSnapshot(process);
        if (!snapshot.isEmpty()) {
            observation.highCardinalityKeyValue("input.value", truncate(snapshot));
        }

        observation.start();
        observation.stop();

        log.debug("Recorded lifecycle state: {} (runId: {})", state, runId);
    }

    // --- Utility Methods ---

    /** Extracts the goal name from the agent process. */
    private String extractGoalName(AgentProcess process) {
        if (process.getGoal() != null) {
            return process.getGoal().getName();
        } else if (!process.getAgent().getGoals().isEmpty()) {
            return process.getAgent().getGoals().iterator().next().getName();
        }
        return "unknown";
    }

    /** Returns a string representation of all objects on the blackboard. */
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

    /** Formats plan actions as a numbered list with goal. */
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

    /** Truncates a string to the configured max attribute length. */
    private String truncate(String value) {
        if (value == null) return "";
        int maxLength = properties.getMaxAttributeLength();
        return value.length() > maxLength ? value.substring(0, maxLength) + "..." : value;
    }
}
