package com.embabel.agent.observability.observation;

import io.micrometer.observation.Observation;

/**
 * Custom observation context for Embabel agent events.
 * Carries metadata for tracing hierarchy (runId, parentRunId, isRoot).
 *
 * @author Quantpulsar 2025-2026
 */
public class EmbabelObservationContext extends Observation.Context {

    public enum EventType {
        AGENT_PROCESS,
        ACTION,
        GOAL,
        TOOL_CALL,
        PLANNING,
        STATE_TRANSITION,
        LIFECYCLE
    }

    private final boolean root;
    private final String runId;
    private final EventType eventType;
    private final String parentRunId;

    public EmbabelObservationContext(boolean root, String runId, String name,
                                     EventType eventType, String parentRunId) {
        this.root = root;
        this.runId = runId;
        this.eventType = eventType;
        this.parentRunId = parentRunId;
        setName(name);
    }

    /** Creates context for a root agent (starts new trace). */
    public static EmbabelObservationContext rootAgent(String runId, String agentName) {
        return new EmbabelObservationContext(true, runId, agentName, EventType.AGENT_PROCESS, null);
    }

    /** Creates context for a subagent (child of parent agent). */
    public static EmbabelObservationContext subAgent(String runId, String agentName, String parentRunId) {
        return new EmbabelObservationContext(false, runId, agentName, EventType.AGENT_PROCESS, parentRunId);
    }

    /** Creates context for an action. */
    public static EmbabelObservationContext action(String runId, String actionName) {
        return new EmbabelObservationContext(false, runId, actionName, EventType.ACTION, null);
    }

    /** Creates context for a goal achievement. */
    public static EmbabelObservationContext goal(String runId, String goalName) {
        return new EmbabelObservationContext(false, runId, goalName, EventType.GOAL, null);
    }

    /** Creates context for a tool call. */
    public static EmbabelObservationContext toolCall(String runId, String toolName) {
        return new EmbabelObservationContext(false, runId, toolName, EventType.TOOL_CALL, null);
    }

    /** Creates context for a planning event. */
    public static EmbabelObservationContext planning(String runId, String planningName) {
        return new EmbabelObservationContext(false, runId, planningName, EventType.PLANNING, null);
    }

    /** Creates context for a state transition. */
    public static EmbabelObservationContext stateTransition(String runId, String stateName) {
        return new EmbabelObservationContext(false, runId, stateName, EventType.STATE_TRANSITION, null);
    }

    /** Creates context for a lifecycle state event. */
    public static EmbabelObservationContext lifecycle(String runId, String lifecycleState) {
        return new EmbabelObservationContext(false, runId, lifecycleState, EventType.LIFECYCLE, null);
    }

    public boolean isRoot() {
        return root;
    }

    public String getRunId() {
        return runId;
    }

    public EventType getEventType() {
        return eventType;
    }

    public String getParentRunId() {
        return parentRunId;
    }
}
