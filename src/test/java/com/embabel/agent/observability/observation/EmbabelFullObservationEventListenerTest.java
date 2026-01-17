package com.embabel.agent.observability.observation;

import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.api.event.*;
import com.embabel.agent.core.Agent;
import com.embabel.agent.core.AgentProcess;
import com.embabel.agent.core.Blackboard;
import com.embabel.agent.core.Goal;
import com.embabel.agent.core.ProcessOptions;
import com.embabel.agent.observability.ObservabilityProperties;
import com.embabel.plan.Plan;
import io.micrometer.observation.ObservationRegistry;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelBaggageManager;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * Tests for EmbabelFullObservationEventListener.
 *
 * <p>Validates observation spans created for various agent events:
 * agent lifecycle, actions, goals, tools, planning, state transitions.
 */
class EmbabelFullObservationEventListenerTest {

    // OpenTelemetry components for capturing spans
    private InMemorySpanExporter spanExporter;
    private OpenTelemetrySdk openTelemetry;
    private Tracer tracer;
    private io.opentelemetry.api.trace.Tracer otelTracer;
    private ObservationRegistry observationRegistry;
    private EmbabelTracingObservationHandler handler;
    private ObservabilityProperties properties;
    private EmbabelFullObservationEventListener listener;

    @BeforeEach
    void setUp() {
        // In-memory exporter captures spans for assertions
        spanExporter = InMemorySpanExporter.create();

        // Configure OpenTelemetry SDK with simple span processor
        SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                .build();

        openTelemetry = OpenTelemetrySdk.builder()
                .setTracerProvider(tracerProvider)
                .setPropagators(ContextPropagators.noop())
                .build();

        otelTracer = openTelemetry.getTracer("test");

        // Bridge Micrometer Tracer to OpenTelemetry
        OtelCurrentTraceContext otelCurrentTraceContext = new OtelCurrentTraceContext();
        OtelBaggageManager baggageManager = new OtelBaggageManager(
                otelCurrentTraceContext,
                Collections.emptyList(),
                Collections.emptyList()
        );
        tracer = new OtelTracer(otelTracer, otelCurrentTraceContext, event -> {}, baggageManager);

        // Create observation handler and registry
        handler = new EmbabelTracingObservationHandler(tracer, otelTracer);
        observationRegistry = ObservationRegistry.create();
        observationRegistry.observationConfig().observationHandler(handler);

        // Create listener under test with default properties
        properties = new ObservabilityProperties();
        listener = new EmbabelFullObservationEventListener(observationRegistry, properties);
    }

    @AfterEach
    void tearDown() {
        // Clear captured spans between tests
        spanExporter.reset();
    }

    // --- Constructor Tests ---

    @Test
    @DisplayName("Constructor should create listener with required dependencies")
    void constructor_shouldCreateListener() {
        // Verify listener is instantiated without errors
        EmbabelFullObservationEventListener listener =
                new EmbabelFullObservationEventListener(observationRegistry, properties);
        assertThat(listener).isNotNull();
    }

    // --- Agent Lifecycle Tests ---

    @Test
    @DisplayName("AgentProcessCreationEvent should start observation span")
    void agentProcessCreationEvent_shouldStartObservation() {
        // Setup: create mock agent process
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        AgentProcessCreationEvent event = new AgentProcessCreationEvent(process);

        // Execute: start and complete agent
        listener.onProcessEvent(event);
        AgentProcessCompletedEvent completedEvent = new AgentProcessCompletedEvent(process);
        listener.onProcessEvent(completedEvent);

        // Verify: one span created with agent name
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(1);
        assertThat(spans.get(0).getName()).isEqualTo("TestAgent");
    }

    @Test
    @DisplayName("AgentProcessCreationEvent should set correct attributes for root agent")
    void agentProcessCreationEvent_shouldSetCorrectAttributes() {
        // Setup: root agent with GOAP planner
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        when(process.getProcessOptions().getPlannerType()).thenReturn(PlannerType.GOAP);

        // Execute: agent lifecycle
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: semantic attributes are set correctly
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData span = spans.get(0);

        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.agent.name")))
                .isEqualTo("TestAgent");
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.agent.is_subagent")))
                .isEqualTo("false");
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.operation.name")))
                .isEqualTo("agent");
    }

    @Test
    @DisplayName("AgentProcessCreationEvent should identify subagent correctly")
    void agentProcessCreationEvent_shouldIdentifySubagent() {
        // Setup: parent agent and child subagent
        AgentProcess parentProcess = createMockAgentProcess("parent-run", "ParentAgent", null);
        AgentProcess subProcess = createMockAgentProcess("sub-run", "SubAgent", "parent-run");

        // Execute: start parent, then subagent, complete in reverse order
        listener.onProcessEvent(new AgentProcessCreationEvent(parentProcess));
        listener.onProcessEvent(new AgentProcessCreationEvent(subProcess));
        listener.onProcessEvent(new AgentProcessCompletedEvent(subProcess));
        listener.onProcessEvent(new AgentProcessCompletedEvent(parentProcess));

        // Verify: subagent has correct parent reference
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);

        SpanData subAgentSpan = spans.stream()
                .filter(s -> s.getName().equals("SubAgent"))
                .findFirst()
                .orElseThrow();

        assertThat(subAgentSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.agent.is_subagent")))
                .isEqualTo("true");
        assertThat(subAgentSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.agent.parent_id")))
                .isEqualTo("parent-run");
    }

    @Test
    @DisplayName("AgentProcessCompletedEvent should set completed status")
    void agentProcessCompletedEvent_shouldSetCompletedStatus() {
        // Setup and execute: simple agent lifecycle
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: status attribute is "completed"
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData span = spans.get(0);
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.agent.status")))
                .isEqualTo("completed");
    }

    @Test
    @DisplayName("AgentProcessFailedEvent should set failed status and error")
    void agentProcessFailedEvent_shouldSetFailedStatus() {
        // Setup: agent with failure info
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        when(process.getFailureInfo()).thenReturn("Something went wrong");

        // Execute: start then fail
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(new AgentProcessFailedEvent(process));

        // Verify: status attribute is "failed"
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData span = spans.get(0);
        assertThat(span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.agent.status")))
                .isEqualTo("failed");
    }

    // --- Action Tests ---

    @Test
    @DisplayName("ActionExecutionStartEvent should start action observation")
    void actionExecutionStartEvent_shouldStartObservation() {
        // Setup: agent process and mock action event
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        ActionExecutionStartEvent startEvent = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
        ActionExecutionResultEvent resultEvent = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");

        // Execute: full action lifecycle within agent
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(startEvent);
        listener.onProcessEvent(resultEvent);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: action span with correct operation name
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans).hasSize(2);

        SpanData actionSpan = spans.stream()
                .filter(s -> s.getName().equals("MyAction"))
                .findFirst()
                .orElseThrow();

        assertThat(actionSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.operation.name")))
                .isEqualTo("execute_action");
    }

    @Test
    @DisplayName("ActionExecutionResultEvent should complete action observation with status")
    void actionExecutionResultEvent_shouldCompleteObservation() {
        // Setup: agent and action events
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        ActionExecutionStartEvent startEvent = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
        ActionExecutionResultEvent resultEvent = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(startEvent);
        listener.onProcessEvent(resultEvent);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: action status recorded
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData actionSpan = spans.stream()
                .filter(s -> s.getName().equals("MyAction"))
                .findFirst()
                .orElseThrow();

        assertThat(actionSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.action.status")))
                .isEqualTo("SUCCESS");
    }

    // --- Tool Call Tests ---

    @Test
    @DisplayName("ToolCallRequestEvent should start tool observation when enabled")
    void toolCallRequestEvent_shouldStartObservation_whenEnabled() {
        // Setup: enable tool call tracing
        properties.setTraceToolCalls(true);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        ToolCallRequestEvent toolEvent = createToolCallRequestEvent(process, "WebSearch", "query input");
        ToolCallResponseEvent responseEvent = createToolCallResponseEvent(process, "WebSearch");

        // Execute: tool call within agent
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(toolEvent);
        listener.onProcessEvent(responseEvent);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: tool span created with correct attributes
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData toolSpan = spans.stream()
                .filter(s -> s.getName().equals("tool:WebSearch"))
                .findFirst()
                .orElseThrow();

        assertThat(toolSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.operation.name")))
                .isEqualTo("execute_tool");
        assertThat(toolSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.tool.name")))
                .isEqualTo("WebSearch");
    }

    @Test
    @DisplayName("ToolCallRequestEvent should not create observation when disabled")
    void toolCallRequestEvent_shouldNotCreateObservation_whenDisabled() {
        // Setup: disable tool call tracing (default)
        properties.setTraceToolCalls(false);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);

        // Execute: tool call should be ignored
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(createToolCallRequestEvent(process, "WebSearch", "query"));
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: no tool spans created
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans.stream().noneMatch(s -> s.getName().contains("tool:"))).isTrue();
    }

    // --- Goal Tests ---

    @Test
    @DisplayName("GoalAchievedEvent should create instant observation")
    void goalAchievedEvent_shouldCreateInstantObservation() {
        // Setup: agent and goal (GoalAchievedEvent needs plan.Goal and WorldState)
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        com.embabel.plan.Goal planGoal = mock(com.embabel.plan.Goal.class);
        com.embabel.plan.WorldState worldState = mock(com.embabel.plan.WorldState.class);
        when(planGoal.getName()).thenReturn("com.example.MyGoal");
        GoalAchievedEvent goalEvent = new GoalAchievedEvent(process, worldState, planGoal);

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(goalEvent);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: goal span with short name extracted
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData goalSpan = spans.stream()
                .filter(s -> s.getName().equals("goal:MyGoal"))
                .findFirst()
                .orElseThrow();

        assertThat(goalSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.goal.short_name")))
                .isEqualTo("MyGoal");
    }

    // --- Planning Tests ---

    @Test
    @DisplayName("AgentProcessReadyToPlanEvent should create observation when enabled")
    void readyToPlanEvent_shouldCreateObservation_whenEnabled() {
        // Setup: enable planning tracing
        properties.setTracePlanning(true);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        AgentProcessReadyToPlanEvent event = mock(AgentProcessReadyToPlanEvent.class);
        when(event.getAgentProcess()).thenReturn(process);
        when(event.getWorldState()).thenReturn(null);

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(event);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: planning:ready span created
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData planningSpan = spans.stream()
                .filter(s -> s.getName().equals("planning:ready"))
                .findFirst()
                .orElseThrow();

        assertThat(planningSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("gen_ai.operation.name")))
                .isEqualTo("planning");
    }

    @Test
    @DisplayName("AgentProcessPlanFormulatedEvent should track plan iterations")
    void planFormulatedEvent_shouldTrackIterations() {
        // Setup: enable planning tracing with mock plan
        properties.setTracePlanning(true);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        Plan plan = createMockPlan();

        AgentProcessPlanFormulatedEvent event1 = mock(AgentProcessPlanFormulatedEvent.class);
        when(event1.getAgentProcess()).thenReturn(process);
        when(event1.getPlan()).thenReturn(plan);
        when(event1.getWorldState()).thenReturn(null);

        AgentProcessPlanFormulatedEvent event2 = mock(AgentProcessPlanFormulatedEvent.class);
        when(event2.getAgentProcess()).thenReturn(process);
        when(event2.getPlan()).thenReturn(plan);
        when(event2.getWorldState()).thenReturn(null);

        // Execute: two plan formulations = replanning
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(event1);
        listener.onProcessEvent(event2);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: second plan marked as replanning with iteration 2
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData replanningSpan = spans.stream()
                .filter(s -> s.getName().equals("planning:replanning"))
                .findFirst()
                .orElseThrow();

        assertThat(replanningSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.plan.is_replanning")))
                .isEqualTo("true");
        assertThat(replanningSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.plan.iteration")))
                .isEqualTo("2");
    }

    @Test
    @DisplayName("Planning events should not be traced when disabled")
    void planningEvents_shouldNotBeTraced_whenDisabled() {
        // Setup: disable planning tracing
        properties.setTracePlanning(false);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);

        AgentProcessReadyToPlanEvent event = mock(AgentProcessReadyToPlanEvent.class);
        when(event.getAgentProcess()).thenReturn(process);

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(event);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: no planning spans
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans.stream().noneMatch(s -> s.getName().contains("planning:"))).isTrue();
    }

    // --- State Transition Tests ---

    @Test
    @DisplayName("StateTransitionEvent should create observation when enabled")
    void stateTransitionEvent_shouldCreateObservation_whenEnabled() {
        // Setup: enable state transition tracing
        properties.setTraceStateTransitions(true);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);

        StateTransitionEvent event = mock(StateTransitionEvent.class);
        when(event.getAgentProcess()).thenReturn(process);
        when(event.getNewState()).thenReturn(new TestState());

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(event);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: state transition span created
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData stateSpan = spans.stream()
                .filter(s -> s.getName().equals("state:TestState"))
                .findFirst()
                .orElseThrow();

        assertThat(stateSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.state.to")))
                .isEqualTo("TestState");
    }

    @Test
    @DisplayName("StateTransitionEvent should not be traced when disabled")
    void stateTransitionEvent_shouldNotBeTraced_whenDisabled() {
        // Setup: disable state transition tracing
        properties.setTraceStateTransitions(false);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);

        StateTransitionEvent event = mock(StateTransitionEvent.class);
        when(event.getAgentProcess()).thenReturn(process);

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(event);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: no state spans
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans.stream().noneMatch(s -> s.getName().contains("state:"))).isTrue();
    }

    // --- Lifecycle State Tests ---

    @Test
    @DisplayName("AgentProcessWaitingEvent should create lifecycle observation when enabled")
    void waitingEvent_shouldCreateObservation_whenEnabled() {
        // Setup: enable lifecycle state tracing
        properties.setTraceLifecycleStates(true);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        AgentProcessWaitingEvent event = new AgentProcessWaitingEvent(process);

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(event);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: lifecycle:waiting span with WAITING state
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData lifecycleSpan = spans.stream()
                .filter(s -> s.getName().equals("lifecycle:waiting"))
                .findFirst()
                .orElseThrow();

        assertThat(lifecycleSpan.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("embabel.lifecycle.state")))
                .isEqualTo("WAITING");
    }

    @Test
    @DisplayName("AgentProcessPausedEvent should create lifecycle observation")
    void pausedEvent_shouldCreateObservation() {
        // Setup
        properties.setTraceLifecycleStates(true);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        AgentProcessPausedEvent event = new AgentProcessPausedEvent(process);

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(event);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: paused lifecycle span exists
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans.stream().anyMatch(s -> s.getName().equals("lifecycle:paused"))).isTrue();
    }

    @Test
    @DisplayName("AgentProcessStuckEvent should create lifecycle observation")
    void stuckEvent_shouldCreateObservation() {
        // Setup
        properties.setTraceLifecycleStates(true);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);
        AgentProcessStuckEvent event = new AgentProcessStuckEvent(process);

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(event);
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: stuck lifecycle span exists
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans.stream().anyMatch(s -> s.getName().equals("lifecycle:stuck"))).isTrue();
    }

    @Test
    @DisplayName("Lifecycle events should not be traced when disabled")
    void lifecycleEvents_shouldNotBeTraced_whenDisabled() {
        // Setup: disable lifecycle tracing
        properties.setTraceLifecycleStates(false);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);

        // Execute: all lifecycle events
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(new AgentProcessWaitingEvent(process));
        listener.onProcessEvent(new AgentProcessPausedEvent(process));
        listener.onProcessEvent(new AgentProcessStuckEvent(process));
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: no lifecycle spans
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        assertThat(spans.stream().noneMatch(s -> s.getName().contains("lifecycle:"))).isTrue();
    }

    // --- Truncation Tests ---

    @Test
    @DisplayName("Long attribute values should be truncated")
    void longAttributeValues_shouldBeTruncated() {
        // Setup: set max attribute length to 50
        properties.setMaxAttributeLength(50);
        AgentProcess process = createMockAgentProcess("run-1", "TestAgent", null);

        // Create long blackboard content (100 chars)
        String longContent = "A".repeat(100);
        Blackboard blackboard = process.getBlackboard();
        when(blackboard.getObjects()).thenReturn(List.of(new TestObject(longContent)));

        // Execute
        listener.onProcessEvent(new AgentProcessCreationEvent(process));
        listener.onProcessEvent(new AgentProcessCompletedEvent(process));

        // Verify: input.value is truncated with "..."
        List<SpanData> spans = spanExporter.getFinishedSpanItems();
        SpanData span = spans.get(0);

        String inputValue = span.getAttributes().get(io.opentelemetry.api.common.AttributeKey.stringKey("input.value"));
        assertThat(inputValue).isNotNull();
        assertThat(inputValue.length()).isLessThanOrEqualTo(53); // 50 + "..."
        assertThat(inputValue).endsWith("...");
    }

    // --- Helper Methods ---

    /**
     * Creates a mock AgentProcess with standard configuration.
     */
    private AgentProcess createMockAgentProcess(String runId, String agentName, String parentId) {
        AgentProcess process = mock(AgentProcess.class);
        Agent agent = mock(Agent.class);
        Blackboard blackboard = mock(Blackboard.class);
        ProcessOptions processOptions = mock(ProcessOptions.class);
        Goal goal = mock(Goal.class);

        when(process.getId()).thenReturn(runId);
        when(process.getAgent()).thenReturn(agent);
        when(process.getBlackboard()).thenReturn(blackboard);
        when(process.getProcessOptions()).thenReturn(processOptions);
        when(process.getParentId()).thenReturn(parentId);
        when(process.getGoal()).thenReturn(goal);

        when(agent.getName()).thenReturn(agentName);
        when(agent.getGoals()).thenReturn(Set.of(goal));

        when(goal.getName()).thenReturn("TestGoal");

        when(blackboard.getObjects()).thenReturn(Collections.emptyList());
        when(blackboard.lastResult()).thenReturn(null);

        when(processOptions.getPlannerType()).thenReturn(PlannerType.GOAP);

        return process;
    }

    /**
     * Creates a mock ActionExecutionStartEvent with action details.
     */
    private ActionExecutionStartEvent createMockActionStartEvent(AgentProcess process, String fullName, String shortName) {
        ActionExecutionStartEvent event = mock(ActionExecutionStartEvent.class);
        when(event.getAgentProcess()).thenReturn(process);

        // Mock action with required methods
        com.embabel.agent.core.Action action = mock(com.embabel.agent.core.Action.class);
        when(action.getName()).thenReturn(fullName);
        when(action.shortName()).thenReturn(shortName);
        when(action.getDescription()).thenReturn("Test action description");

        lenient().doReturn(action).when(event).getAction();
        return event;
    }

    /**
     * Creates a mock ActionExecutionResultEvent with status.
     */
    private ActionExecutionResultEvent createMockActionResultEvent(AgentProcess process, String actionName, String statusName) {
        ActionExecutionResultEvent event = mock(ActionExecutionResultEvent.class);
        when(event.getAgentProcess()).thenReturn(process);
        when(event.getRunningTime()).thenReturn(Duration.ofMillis(100));

        // Mock action
        com.embabel.agent.core.Action action = mock(com.embabel.agent.core.Action.class);
        when(action.getName()).thenReturn(actionName);
        lenient().doReturn(action).when(event).getAction();

        // Mock ActionStatus with nested status
        com.embabel.agent.core.ActionStatus actionStatus = mock(com.embabel.agent.core.ActionStatus.class);
        com.embabel.agent.core.ActionStatusCode statusCode = mock(com.embabel.agent.core.ActionStatusCode.class);
        when(statusCode.name()).thenReturn(statusName);
        when(actionStatus.getStatus()).thenReturn(statusCode);
        lenient().doReturn(actionStatus).when(event).getActionStatus();

        return event;
    }

    /**
     * Creates a mock ToolCallRequestEvent.
     */
    private ToolCallRequestEvent createToolCallRequestEvent(AgentProcess process, String toolName, String input) {
        ToolCallRequestEvent event = mock(ToolCallRequestEvent.class);
        when(event.getAgentProcess()).thenReturn(process);
        when(event.getTool()).thenReturn(toolName);
        when(event.getToolInput()).thenReturn(input);
        return event;
    }

    /**
     * Creates a mock ToolCallResponseEvent.
     */
    private ToolCallResponseEvent createToolCallResponseEvent(AgentProcess process, String toolName) {
        return createToolCallResponseEvent(process, toolName, "Tool result: " + toolName, null);
    }

    /**
     * Creates a mock ToolCallResponseEvent with specific result.
     */
    private ToolCallResponseEvent createToolCallResponseEvent(AgentProcess process, String toolName,
                                                               String successResult, Throwable error) {
        ToolCallRequestEvent request = mock(ToolCallRequestEvent.class);
        when(request.getTool()).thenReturn(toolName);

        // Create a mock Result object for the reflection-based extraction
        MockResult mockResult = new MockResult(successResult, error);

        // Use Mockito's Answer to intercept all method calls
        ToolCallResponseEvent event = mock(ToolCallResponseEvent.class, invocation -> {
            String methodName = invocation.getMethod().getName();
            if (methodName.equals("getResult") || methodName.startsWith("getResult-")) {
                return mockResult;
            }
            if (methodName.equals("getAgentProcess")) {
                return process;
            }
            if (methodName.equals("getRequest")) {
                return request;
            }
            if (methodName.equals("getRunningTime")) {
                return Duration.ofMillis(50);
            }
            return null;
        });

        return event;
    }

    /**
     * A mock Result class that mimics Kotlin's Result<T>.
     * Provides getOrNull() and exceptionOrNull() methods that the listeners call via reflection.
     */
    static class MockResult {
        private final Object successValue;
        private final Throwable error;

        MockResult(Object successValue, Throwable error) {
            this.successValue = successValue;
            this.error = error;
        }

        public Object getOrNull() {
            return error == null ? successValue : null;
        }

        public Throwable exceptionOrNull() {
            return error;
        }
    }

    /**
     * Creates a mock Plan with one action.
     */
    private Plan createMockPlan() {
        Plan plan = mock(Plan.class);

        // Mock plan action (from com.embabel.plan.Action)
        com.embabel.plan.Action planAction = mock(com.embabel.plan.Action.class);
        when(planAction.getName()).thenReturn("PlanStep1");

        // Mock plan goal
        com.embabel.plan.Goal planGoal = mock(com.embabel.plan.Goal.class);
        when(planGoal.getName()).thenReturn("PlanGoal");

        when(plan.getActions()).thenReturn(List.of(planAction));
        when(plan.getGoal()).thenReturn(planGoal);
        return plan;
    }

    // --- Test Helper Classes ---

    private static class TestState {
        @Override
        public String toString() {
            return "TestState";
        }
    }

    private record TestObject(String content) {
        @Override
        public String toString() {
            return content;
        }
    }
}
