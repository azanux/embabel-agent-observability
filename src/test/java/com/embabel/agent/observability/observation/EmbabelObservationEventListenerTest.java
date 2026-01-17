package com.embabel.agent.observability.observation;

import com.embabel.agent.api.common.PlannerType;
import com.embabel.agent.api.event.*;
import com.embabel.agent.core.*;
import com.embabel.agent.observability.ObservabilityProperties;
import com.embabel.plan.Plan;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.OpenTelemetrySdk;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for EmbabelObservationEventListener.
 *
 * Covers:
 * - Initialization and tracer setup
 * - Agent lifecycle events (creation, completion, failure)
 * - Action execution events
 * - Goal achievement events
 * - Tool call events (when enabled)
 * - Planning events (when enabled)
 * - State transitions (when enabled)
 * - Lifecycle states (when enabled)
 * - Object binding events (when enabled)
 * - Span hierarchy (parent-child relationships)
 */
@ExtendWith(MockitoExtension.class)
class EmbabelObservationEventListenerTest {

    // ================================================================================
    // INITIALIZATION TESTS
    // ================================================================================

    @Nested
    @DisplayName("Initialization Tests")
    class InitializationTests {

        @Mock
        private ObjectProvider<OpenTelemetry> openTelemetryProvider;

        @Mock
        private OpenTelemetry openTelemetry;

        @Mock
        private Tracer tracer;

        private ObservabilityProperties properties;

        @BeforeEach
        void setUp() {
            properties = new ObservabilityProperties();
        }

        @Test
        @DisplayName("Constructor should create listener without errors")
        void constructor_shouldCreateListener() {
            EmbabelObservationEventListener listener =
                    new EmbabelObservationEventListener(openTelemetryProvider, properties);

            assertThat(listener).isNotNull();
        }

        @Test
        @DisplayName("Should initialize tracer when OpenTelemetry is available")
        void afterSingletonsInstantiated_shouldInitializeTracer_whenOpenTelemetryAvailable() {
            when(openTelemetryProvider.getIfAvailable()).thenReturn(openTelemetry);
            when(openTelemetry.getTracer(anyString(), anyString())).thenReturn(tracer);

            EmbabelObservationEventListener listener =
                    new EmbabelObservationEventListener(openTelemetryProvider, properties);
            listener.afterSingletonsInstantiated();

            verify(openTelemetry).getTracer(properties.getTracerName(), properties.getTracerVersion());
        }

        @Test
        @DisplayName("Should handle missing OpenTelemetry gracefully")
        void afterSingletonsInstantiated_shouldHandleMissingOpenTelemetry() {
            when(openTelemetryProvider.getIfAvailable()).thenReturn(null);

            EmbabelObservationEventListener listener =
                    new EmbabelObservationEventListener(openTelemetryProvider, properties);
            listener.afterSingletonsInstantiated();

            verify(openTelemetryProvider).getIfAvailable();
        }

        @Test
        @DisplayName("Should use custom tracer name from properties")
        void afterSingletonsInstantiated_shouldUseCustomTracerName() {
            properties.setTracerName("custom-tracer");
            properties.setTracerVersion("1.0.0");

            when(openTelemetryProvider.getIfAvailable()).thenReturn(openTelemetry);
            when(openTelemetry.getTracer(anyString(), anyString())).thenReturn(tracer);

            EmbabelObservationEventListener listener =
                    new EmbabelObservationEventListener(openTelemetryProvider, properties);
            listener.afterSingletonsInstantiated();

            verify(openTelemetry).getTracer("custom-tracer", "1.0.0");
        }
    }

    // ================================================================================
    // INTEGRATION TESTS WITH REAL OPENTELEMETRY
    // ================================================================================

    @Nested
    @DisplayName("Agent Lifecycle Integration Tests")
    class AgentLifecycleIntegrationTests {

        private InMemorySpanExporter spanExporter;
        private OpenTelemetrySdk openTelemetry;
        private EmbabelObservationEventListener listener;
        private ObservabilityProperties properties;

        @BeforeEach
        void setUp() {
            spanExporter = InMemorySpanExporter.create();

            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build();

            openTelemetry = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .setPropagators(ContextPropagators.noop())
                    .build();

            properties = new ObservabilityProperties();

            // Use real OpenTelemetry via ObjectProvider
            ObjectProvider<OpenTelemetry> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(openTelemetry);

            listener = new EmbabelObservationEventListener(provider, properties);
            listener.afterSingletonsInstantiated();
        }

        @AfterEach
        void tearDown() {
            spanExporter.reset();
        }

        @Test
        @DisplayName("Agent creation should create a span with correct attributes")
        void onAgentProcessCreation_shouldCreateSpanWithAttributes() {
            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertThat(spans).hasSize(1);

            SpanData agentSpan = spans.get(0);
            assertThat(agentSpan.getName()).isEqualTo("TestAgent");
            assertThat(agentSpan.getKind()).isEqualTo(SpanKind.SERVER);
            assertThat(agentSpan.getAttributes().get(AttributeKey.stringKey("embabel.agent.name")))
                    .isEqualTo("TestAgent");
            assertThat(agentSpan.getAttributes().get(AttributeKey.stringKey("embabel.agent.run_id")))
                    .isEqualTo("run-1");
            assertThat(agentSpan.getAttributes().get(AttributeKey.booleanKey("embabel.agent.is_subagent")))
                    .isFalse();
        }

        @Test
        @DisplayName("Agent completion should set OK status")
        void onAgentProcessCompleted_shouldSetOkStatus() {
            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertThat(spans).hasSize(1);

            SpanData agentSpan = spans.get(0);
            assertThat(agentSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.OK);
            assertThat(agentSpan.getAttributes().get(AttributeKey.stringKey("embabel.agent.status")))
                    .isEqualTo("completed");
        }

        @Test
        @DisplayName("Agent failure should set ERROR status")
        void onAgentProcessFailed_shouldSetErrorStatus() {
            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");
            when(process.getFailureInfo()).thenReturn("Something went wrong");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new AgentProcessFailedEvent(process));

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertThat(spans).hasSize(1);

            SpanData agentSpan = spans.get(0);
            assertThat(agentSpan.getStatus().getStatusCode()).isEqualTo(StatusCode.ERROR);
            assertThat(agentSpan.getAttributes().get(AttributeKey.stringKey("embabel.agent.status")))
                    .isEqualTo("failed");
            assertThat(agentSpan.getAttributes().get(AttributeKey.stringKey("embabel.agent.error")))
                    .isEqualTo("Something went wrong");
        }

        @Test
        @DisplayName("Subagent should be child of parent agent")
        void subagent_shouldBeChildOfParentAgent() {
            AgentProcess parentProcess = createMockAgentProcess("parent-run", "ParentAgent");
            AgentProcess childProcess = createMockAgentProcess("child-run", "ChildAgent");
            when(childProcess.getParentId()).thenReturn("parent-run");

            // Start parent
            listener.onProcessEvent(new AgentProcessCreationEvent(parentProcess));

            // Start child (should be nested under parent)
            listener.onProcessEvent(new AgentProcessCreationEvent(childProcess));
            listener.onProcessEvent(new AgentProcessCompletedEvent(childProcess));

            // Complete parent
            listener.onProcessEvent(new AgentProcessCompletedEvent(parentProcess));

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertThat(spans).hasSize(2);

            SpanData parentSpan = findSpanByName(spans, "ParentAgent");
            SpanData childSpan = findSpanByName(spans, "ChildAgent");

            assertThat(parentSpan).isNotNull();
            assertThat(childSpan).isNotNull();

            // Child should have parent as its parent span
            assertThat(childSpan.getParentSpanId()).isEqualTo(parentSpan.getSpanId());
            assertThat(childSpan.getTraceId()).isEqualTo(parentSpan.getTraceId());

            // Child should be marked as subagent
            assertThat(childSpan.getAttributes().get(AttributeKey.booleanKey("embabel.agent.is_subagent")))
                    .isTrue();
            assertThat(childSpan.getKind()).isEqualTo(SpanKind.INTERNAL);
        }
    }

    // ================================================================================
    // ACTION TESTS
    // ================================================================================

    @Nested
    @DisplayName("Action Execution Tests")
    class ActionExecutionTests {

        private InMemorySpanExporter spanExporter;
        private EmbabelObservationEventListener listener;
        private ObservabilityProperties properties;

        @BeforeEach
        void setUp() {
            spanExporter = InMemorySpanExporter.create();

            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build();

            OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .setPropagators(ContextPropagators.noop())
                    .build();

            properties = new ObservabilityProperties();

            ObjectProvider<OpenTelemetry> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(openTelemetry);

            listener = new EmbabelObservationEventListener(provider, properties);
            listener.afterSingletonsInstantiated();
        }

        @AfterEach
        void tearDown() {
            spanExporter.reset();
        }

        @Test
        @DisplayName("Action should be child of agent span")
        void action_shouldBeChildOfAgentSpan() {
            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");
            ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
            ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(actionStart);
            listener.onProcessEvent(actionResult);
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertThat(spans).hasSize(2);

            SpanData agentSpan = findSpanByName(spans, "TestAgent");
            SpanData actionSpan = findSpanByName(spans, "MyAction");

            assertThat(agentSpan).isNotNull();
            assertThat(actionSpan).isNotNull();

            // Action should be child of agent
            assertThat(actionSpan.getParentSpanId()).isEqualTo(agentSpan.getSpanId());
            assertThat(actionSpan.getTraceId()).isEqualTo(agentSpan.getTraceId());
        }

        @Test
        @DisplayName("Action span should have correct attributes")
        void action_shouldHaveCorrectAttributes() {
            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");
            ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "com.example.MyAction", "MyAction");
            ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "com.example.MyAction", "SUCCESS");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(actionStart);
            listener.onProcessEvent(actionResult);
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            SpanData actionSpan = findSpanByName(spans, "MyAction");

            assertThat(actionSpan.getAttributes().get(AttributeKey.stringKey("embabel.action.name")))
                    .isEqualTo("com.example.MyAction");
            assertThat(actionSpan.getAttributes().get(AttributeKey.stringKey("embabel.action.short_name")))
                    .isEqualTo("MyAction");
            assertThat(actionSpan.getAttributes().get(AttributeKey.stringKey("embabel.action.status")))
                    .isEqualTo("SUCCESS");
            assertThat(actionSpan.getAttributes().get(AttributeKey.stringKey("gen_ai.operation.name")))
                    .isEqualTo("execute_action");
        }

        @Test
        @DisplayName("Multiple actions should all be children of agent")
        void multipleActions_shouldAllBeChildrenOfAgent() {
            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));

            // Action 1
            ActionExecutionStartEvent action1Start = createMockActionStartEvent(process, "Action1", "Action1");
            ActionExecutionResultEvent action1Result = createMockActionResultEvent(process, "Action1", "SUCCESS");
            listener.onProcessEvent(action1Start);
            listener.onProcessEvent(action1Result);

            // Action 2
            ActionExecutionStartEvent action2Start = createMockActionStartEvent(process, "Action2", "Action2");
            ActionExecutionResultEvent action2Result = createMockActionResultEvent(process, "Action2", "SUCCESS");
            listener.onProcessEvent(action2Start);
            listener.onProcessEvent(action2Result);

            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertThat(spans).hasSize(3); // 1 agent + 2 actions

            SpanData agentSpan = findSpanByName(spans, "TestAgent");
            SpanData action1Span = findSpanByName(spans, "Action1");
            SpanData action2Span = findSpanByName(spans, "Action2");

            assertThat(action1Span.getParentSpanId()).isEqualTo(agentSpan.getSpanId());
            assertThat(action2Span.getParentSpanId()).isEqualTo(agentSpan.getSpanId());
        }
    }

    // ================================================================================
    // GOAL TESTS
    // ================================================================================

    @Nested
    @DisplayName("Goal Achievement Tests")
    class GoalAchievementTests {

        private InMemorySpanExporter spanExporter;
        private EmbabelObservationEventListener listener;

        @BeforeEach
        void setUp() {
            spanExporter = InMemorySpanExporter.create();

            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build();

            OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .setPropagators(ContextPropagators.noop())
                    .build();

            ObservabilityProperties properties = new ObservabilityProperties();

            ObjectProvider<OpenTelemetry> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(openTelemetry);

            listener = new EmbabelObservationEventListener(provider, properties);
            listener.afterSingletonsInstantiated();
        }

        @AfterEach
        void tearDown() {
            spanExporter.reset();
        }

        @Test
        @DisplayName("Goal achieved should create span under agent")
        void goalAchieved_shouldCreateSpanUnderAgent() {
            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");
            GoalAchievedEvent goalEvent = createMockGoalAchievedEvent(process, "com.example.MyGoal");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(goalEvent);
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertThat(spans).hasSize(2);

            SpanData agentSpan = findSpanByName(spans, "TestAgent");
            SpanData goalSpan = findSpanByName(spans, "goal:MyGoal");

            assertThat(goalSpan).isNotNull();
            assertThat(goalSpan.getParentSpanId()).isEqualTo(agentSpan.getSpanId());
            assertThat(goalSpan.getAttributes().get(AttributeKey.stringKey("embabel.goal.name")))
                    .isEqualTo("com.example.MyGoal");
            assertThat(goalSpan.getAttributes().get(AttributeKey.stringKey("embabel.event.type")))
                    .isEqualTo("goal_achieved");
        }
    }

    // ================================================================================
    // TOOL CALL TESTS
    // ================================================================================

    @Nested
    @DisplayName("Tool Call Tests")
    class ToolCallTests {

        private InMemorySpanExporter spanExporter;
        private EmbabelObservationEventListener listener;
        private ObservabilityProperties properties;

        @BeforeEach
        void setUp() {
            spanExporter = InMemorySpanExporter.create();

            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build();

            OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .setPropagators(ContextPropagators.noop())
                    .build();

            properties = new ObservabilityProperties();
            properties.setTraceToolCalls(true); // Enable tool call tracing

            ObjectProvider<OpenTelemetry> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(openTelemetry);

            listener = new EmbabelObservationEventListener(provider, properties);
            listener.afterSingletonsInstantiated();
        }

        @AfterEach
        void tearDown() {
            spanExporter.reset();
        }

        @Test
        @DisplayName("Tool call should create span when enabled")
        void toolCall_shouldCreateSpan_whenEnabled() {
            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");
            ToolCallRequestEvent request = createToolCallRequestEvent(process, "WebSearch", "{\"query\": \"test\"}");
            ToolCallResponseEvent response = createToolCallResponseEvent(process, "WebSearch");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(request);
            listener.onProcessEvent(response);
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertThat(spans).hasSize(2); // agent + tool

            SpanData toolSpan = findSpanByName(spans, "tool:WebSearch");
            assertThat(toolSpan).isNotNull();
            assertThat(toolSpan.getAttributes().get(AttributeKey.stringKey("gen_ai.tool.name")))
                    .isEqualTo("WebSearch");
            assertThat(toolSpan.getAttributes().get(AttributeKey.stringKey("input.value")))
                    .isEqualTo("{\"query\": \"test\"}");
        }

        @Test
        @DisplayName("Tool call should NOT create span when disabled")
        void toolCall_shouldNotCreateSpan_whenDisabled() {
            // Disable tool call tracing
            properties.setTraceToolCalls(false);

            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");
            ToolCallRequestEvent request = createToolCallRequestEvent(process, "WebSearch", "{\"query\": \"test\"}");
            ToolCallResponseEvent response = createToolCallResponseEvent(process, "WebSearch");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(request);
            listener.onProcessEvent(response);
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertThat(spans).hasSize(1); // Only agent, no tool span

            assertThat(findSpanByName(spans, "tool:WebSearch")).isNull();
        }
    }

    // ================================================================================
    // PLANNING TESTS
    // ================================================================================

    @Nested
    @DisplayName("Planning Tests")
    class PlanningTests {

        private InMemorySpanExporter spanExporter;
        private EmbabelObservationEventListener listener;
        private ObservabilityProperties properties;

        @BeforeEach
        void setUp() {
            spanExporter = InMemorySpanExporter.create();

            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build();

            OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .setPropagators(ContextPropagators.noop())
                    .build();

            properties = new ObservabilityProperties();
            properties.setTracePlanning(true); // Enable planning tracing

            ObjectProvider<OpenTelemetry> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(openTelemetry);

            listener = new EmbabelObservationEventListener(provider, properties);
            listener.afterSingletonsInstantiated();
        }

        @AfterEach
        void tearDown() {
            spanExporter.reset();
        }

        @Test
        @DisplayName("Planning events should create spans when enabled")
        void planningEvents_shouldCreateSpans_whenEnabled() {
            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");
            AgentProcessReadyToPlanEvent readyEvent = createReadyToPlanEvent(process);
            AgentProcessPlanFormulatedEvent formulatedEvent = createPlanFormulatedEvent(process);

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(readyEvent);
            listener.onProcessEvent(formulatedEvent);
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            List<SpanData> spans = spanExporter.getFinishedSpanItems();
            assertThat(spans).hasSize(3); // agent + ready + formulated

            SpanData readySpan = findSpanByName(spans, "planning:ready");
            SpanData formulatedSpan = findSpanByName(spans, "planning:formulated");

            assertThat(readySpan).isNotNull();
            assertThat(formulatedSpan).isNotNull();
        }

        @Test
        @DisplayName("Replanning should increment iteration counter")
        void replanning_shouldIncrementIterationCounter() {
            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));

            // First plan
            listener.onProcessEvent(createPlanFormulatedEvent(process));

            // Replan
            listener.onProcessEvent(createPlanFormulatedEvent(process));

            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            List<SpanData> spans = spanExporter.getFinishedSpanItems();

            // Find replanning span
            SpanData replanSpan = spans.stream()
                    .filter(s -> s.getName().equals("planning:replanning"))
                    .findFirst()
                    .orElse(null);

            assertThat(replanSpan).isNotNull();
            assertThat(replanSpan.getAttributes().get(AttributeKey.longKey("embabel.plan.iteration")))
                    .isEqualTo(2L);
        }
    }

    // ================================================================================
    // TRACING DISABLED TESTS
    // ================================================================================

    @Nested
    @DisplayName("Tracing Disabled Tests")
    class TracingDisabledTests {

        @Test
        @DisplayName("Events should be ignored when tracer is not available")
        void events_shouldBeIgnored_whenTracerNotAvailable() {
            ObservabilityProperties properties = new ObservabilityProperties();

            ObjectProvider<OpenTelemetry> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(null); // No OpenTelemetry

            EmbabelObservationEventListener listener = new EmbabelObservationEventListener(provider, properties);
            listener.afterSingletonsInstantiated();

            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");

            // These should not throw exceptions
            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            // No way to verify spans since there's no exporter, but no exception = success
        }
    }

    // ================================================================================
    // FULL HIERARCHY TEST
    // ================================================================================

    @Nested
    @DisplayName("Full Hierarchy Integration Tests")
    class FullHierarchyIntegrationTests {

        private InMemorySpanExporter spanExporter;
        private EmbabelObservationEventListener listener;

        @BeforeEach
        void setUp() {
            spanExporter = InMemorySpanExporter.create();

            SdkTracerProvider tracerProvider = SdkTracerProvider.builder()
                    .addSpanProcessor(SimpleSpanProcessor.create(spanExporter))
                    .build();

            OpenTelemetrySdk openTelemetry = OpenTelemetrySdk.builder()
                    .setTracerProvider(tracerProvider)
                    .setPropagators(ContextPropagators.noop())
                    .build();

            ObservabilityProperties properties = new ObservabilityProperties();
            properties.setTraceToolCalls(true);
            properties.setTracePlanning(true);

            ObjectProvider<OpenTelemetry> provider = mock(ObjectProvider.class);
            when(provider.getIfAvailable()).thenReturn(openTelemetry);

            listener = new EmbabelObservationEventListener(provider, properties);
            listener.afterSingletonsInstantiated();
        }

        @AfterEach
        void tearDown() {
            spanExporter.reset();
        }

        @Test
        @DisplayName("Full workflow should produce correct span hierarchy")
        void fullWorkflow_shouldProduceCorrectHierarchy() {
            // Simulate: Agent -> Plan -> Action -> Tool -> Goal
            AgentProcess process = createMockAgentProcess("run-1", "TestAgent");

            listener.onProcessEvent(new AgentProcessCreationEvent(process));
            listener.onProcessEvent(createReadyToPlanEvent(process));
            listener.onProcessEvent(createPlanFormulatedEvent(process));

            ActionExecutionStartEvent actionStart = createMockActionStartEvent(process, "MyAction", "MyAction");
            listener.onProcessEvent(actionStart);

            ToolCallRequestEvent toolRequest = createToolCallRequestEvent(process, "WebSearch", "{}");
            ToolCallResponseEvent toolResponse = createToolCallResponseEvent(process, "WebSearch");
            listener.onProcessEvent(toolRequest);
            listener.onProcessEvent(toolResponse);

            ActionExecutionResultEvent actionResult = createMockActionResultEvent(process, "MyAction", "SUCCESS");
            listener.onProcessEvent(actionResult);

            listener.onProcessEvent(createMockGoalAchievedEvent(process, "TestGoal"));
            listener.onProcessEvent(new AgentProcessCompletedEvent(process));

            List<SpanData> spans = spanExporter.getFinishedSpanItems();

            // Should have: agent, planning:ready, planning:formulated, action, tool, goal
            assertThat(spans).hasSize(6);

            SpanData agentSpan = findSpanByName(spans, "TestAgent");

            // All should be in same trace
            for (SpanData span : spans) {
                assertThat(span.getTraceId()).isEqualTo(agentSpan.getTraceId());
            }

            // Planning, action, goal should be children of agent
            SpanData planReadySpan = findSpanByName(spans, "planning:ready");
            SpanData planFormulatedSpan = findSpanByName(spans, "planning:formulated");
            SpanData actionSpan = findSpanByName(spans, "MyAction");
            SpanData goalSpan = findSpanByName(spans, "goal:TestGoal");

            assertThat(planReadySpan.getParentSpanId()).isEqualTo(agentSpan.getSpanId());
            assertThat(planFormulatedSpan.getParentSpanId()).isEqualTo(agentSpan.getSpanId());
            assertThat(actionSpan.getParentSpanId()).isEqualTo(agentSpan.getSpanId());
            assertThat(goalSpan.getParentSpanId()).isEqualTo(agentSpan.getSpanId());

            // Tool should be child of action
            SpanData toolSpan = findSpanByName(spans, "tool:WebSearch");
            assertThat(toolSpan.getParentSpanId()).isEqualTo(actionSpan.getSpanId());
        }
    }

    // ================================================================================
    // HELPER METHODS
    // ================================================================================

    private static AgentProcess createMockAgentProcess(String runId, String agentName) {
        AgentProcess process = mock(AgentProcess.class);
        Agent agent = mock(Agent.class);
        Blackboard blackboard = mock(Blackboard.class);
        ProcessOptions processOptions = mock(ProcessOptions.class);
        Goal goal = mock(Goal.class);

        lenient().when(process.getId()).thenReturn(runId);
        lenient().when(process.getAgent()).thenReturn(agent);
        lenient().when(process.getBlackboard()).thenReturn(blackboard);
        lenient().when(process.getProcessOptions()).thenReturn(processOptions);
        lenient().when(process.getParentId()).thenReturn(null);
        lenient().when(process.getGoal()).thenReturn(goal);
        lenient().when(process.getFailureInfo()).thenReturn(null);

        lenient().when(agent.getName()).thenReturn(agentName);
        lenient().when(agent.getGoals()).thenReturn(Set.of(goal));
        lenient().when(goal.getName()).thenReturn("TestGoal");

        lenient().when(blackboard.getObjects()).thenReturn(Collections.emptyList());
        lenient().when(blackboard.lastResult()).thenReturn(null);
        lenient().when(processOptions.getPlannerType()).thenReturn(PlannerType.GOAP);

        return process;
    }

    private static ActionExecutionStartEvent createMockActionStartEvent(AgentProcess process, String fullName, String shortName) {
        ActionExecutionStartEvent event = mock(ActionExecutionStartEvent.class);
        when(event.getAgentProcess()).thenReturn(process);

        Action action = mock(Action.class);
        when(action.getName()).thenReturn(fullName);
        when(action.shortName()).thenReturn(shortName);
        when(action.getDescription()).thenReturn("Test action");
        lenient().doReturn(action).when(event).getAction();

        return event;
    }

    private static ActionExecutionResultEvent createMockActionResultEvent(AgentProcess process, String actionName, String status) {
        ActionExecutionResultEvent event = mock(ActionExecutionResultEvent.class);
        when(event.getAgentProcess()).thenReturn(process);
        when(event.getRunningTime()).thenReturn(Duration.ofMillis(100));

        Action action = mock(Action.class);
        when(action.getName()).thenReturn(actionName);
        lenient().doReturn(action).when(event).getAction();

        ActionStatus actionStatus = mock(ActionStatus.class);
        ActionStatusCode statusCode = mock(ActionStatusCode.class);
        when(statusCode.name()).thenReturn(status);
        when(actionStatus.getStatus()).thenReturn(statusCode);
        lenient().doReturn(actionStatus).when(event).getActionStatus();

        return event;
    }

    private static GoalAchievedEvent createMockGoalAchievedEvent(AgentProcess process, String goalName) {
        GoalAchievedEvent event = mock(GoalAchievedEvent.class);
        when(event.getAgentProcess()).thenReturn(process);

        Goal goal = mock(Goal.class);
        when(goal.getName()).thenReturn(goalName);
        when(event.getGoal()).thenReturn(goal);

        return event;
    }

    private static ToolCallRequestEvent createToolCallRequestEvent(AgentProcess process, String toolName, String input) {
        ToolCallRequestEvent event = mock(ToolCallRequestEvent.class);
        lenient().when(event.getAgentProcess()).thenReturn(process);
        lenient().when(event.getTool()).thenReturn(toolName);
        lenient().when(event.getToolInput()).thenReturn(input);
        return event;
    }

    private static ToolCallResponseEvent createToolCallResponseEvent(AgentProcess process, String toolName) {
        return createToolCallResponseEvent(process, toolName, "Tool result: " + toolName, null);
    }

    private static ToolCallResponseEvent createToolCallResponseEvent(AgentProcess process, String toolName,
                                                                      String successResult, Throwable error) {
        ToolCallRequestEvent request = mock(ToolCallRequestEvent.class);
        lenient().when(request.getTool()).thenReturn(toolName);

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

    private static AgentProcessReadyToPlanEvent createReadyToPlanEvent(AgentProcess process) {
        AgentProcessReadyToPlanEvent event = mock(AgentProcessReadyToPlanEvent.class);
        when(event.getAgentProcess()).thenReturn(process);
        return event;
    }

    private static AgentProcessPlanFormulatedEvent createPlanFormulatedEvent(AgentProcess process) {
        AgentProcessPlanFormulatedEvent event = mock(AgentProcessPlanFormulatedEvent.class);
        when(event.getAgentProcess()).thenReturn(process);

        Plan plan = mock(Plan.class);
        when(plan.getActions()).thenReturn(Collections.emptyList());
        when(event.getPlan()).thenReturn(plan);

        return event;
    }

    private static SpanData findSpanByName(List<SpanData> spans, String name) {
        return spans.stream()
                .filter(s -> s.getName().equals(name))
                .findFirst()
                .orElse(null);
    }

}