# Technical Guide - Embabel Agent Observability

## Table of Contents

1. [Overview](#1-overview)
2. [Project Architecture](#2-project-architecture)
3. [Maven Dependencies and Configuration](#3-maven-dependencies-and-configuration)
4. [Configuration Properties](#4-configuration-properties)
5. [The Three Tracing Implementations](#5-the-three-tracing-implementations)
6. [Class Details](#6-class-details)
7. [Integration with Spring AI](#7-integration-with-spring-ai)
8. [Implemented Features for Embabel](#8-implemented-features-for-embabel)
9. [Traces, Metrics, and Observability Compatibility](#9-traces-metrics-and-observability-compatibility)
10. [Usage Guide](#10-usage-guide)
11. [Flow Examples](#11-flow-examples)
12. [Supported Exporters](#12-supported-exporters)
13. [Roadmap - Implemented vs Available Features](#13-roadmap---implemented-vs-available-features)
14. [Complete Reference of Available Events](#14-complete-reference-of-available-events)

---

## 1. Overview

### What is this project?

**Embabel Agent Observability** is a Spring Boot library that provides a comprehensive observability infrastructure for applications based on **Embabel Agent**. It enables:

- **Tracing** the complete execution of AI agents (agents, actions, tools, planning)
- **Generating automatic metrics** on durations and counts
- **Integrating LLM calls** from Spring AI as children of Embabel spans
- **Exporting** to multiple backends (Langfuse, Zipkin, OTLP, etc.)

### Project Information

| Property | Value |
|----------|-------|
| GroupId | `com.embabel.agent` |
| ArtifactId | `embabel-agent-observability` |
| Version | `0.3.2-SNAPSHOT` |
| Java | 21 |
| Spring Boot | 3.5.9 |
| Spring AI | 1.1.1 |
| OpenTelemetry | 2.17.0 |

---

## 2. Project Architecture

### File Structure

```
embabel-agent-observability/
+-- pom.xml                                    # Maven configuration
+-- README.md                                  # Documentation (placeholder)
+-- docs/TECHNICAL_GUIDE.md                    # This guide
+-- src/
    +-- main/
    |   +-- java/com/embabel/agent/observability/
    |   |   +-- ObservabilityProperties.java           # Configuration
    |   |   +-- ObservabilityAutoConfiguration.java    # Main auto-config
    |   |   +-- MicrometerTracingAutoConfiguration.java # Micrometer config
    |   |   +-- OpenTelemetrySdkAutoConfiguration.java # OpenTelemetry config
    |   |   +-- observation/
    |   |       +-- EmbabelObservationContext.java         # Custom context
    |   |       +-- EmbabelFullObservationEventListener.java    # SPRING_OBSERVATION impl
    |   |       +-- EmbabelSpringObservationEventListener.java  # MICROMETER_TRACING impl
    |   |       +-- EmbabelObservationEventListener.java       # OPENTELEMETRY_DIRECT impl
    |   |       +-- EmbabelTracingObservationHandler.java      # Custom handler
    |   |       +-- NonEmbabelTracingObservationHandler.java   # Default handler override
    |   |       +-- ChatModelObservationFilter.java            # Spring AI filter
    |   +-- resources/META-INF/spring/
    |       +-- org.springframework.boot.autoconfigure.AutoConfiguration.imports
    +-- test/
        +-- java/com/embabel/agent/observability/
            +-- ObservabilityPropertiesTest.java
            +-- ObservabilityAutoConfigurationTest.java
            +-- observation/
                +-- ChatModelObservationFilterTest.java
                +-- EmbabelObservationEventListenerTest.java
                +-- EmbabelSpringObservationEventListenerTest.java
                +-- EmbabelTracingObservationHandlerTest.java
                +-- SpringObservationProofOfConceptTest.java
```

### Architecture Diagram

```
+-------------------------------------------------------------+
|                    EMBABEL AGENT                              |
|  +-------------+  +-----------+  +--------+  +---------+     |
|  |   Agent     |  |  Actions  |  |  Tools |  | Planning|     |
|  +------+------+  +-----+-----+  +----+---+  +----+----+     |
+---------+---------------+-----------+------------+-----------+
                          |
                +---------+---------+
                | AgentProcessEvent |
                +---------+---------+
                          |
+-----------------------------+-----------------------------+
|              EMBABEL OBSERVABILITY                        |
|  +------------------------------------------------------+ |
|  |           ObservabilityAutoConfiguration             | |
|  |           (Implementation selection)                 | |
|  +-----------------------+------------------------------+ |
|                          |                                |
|          +---------------+---------------+                |
|          v               v               v                |
|  +--------------++--------------++--------------+         |
|  |    SPRING    ||  MICROMETER  || OPENTELEMETRY|         |
|  | OBSERVATION  ||   TRACING    ||    DIRECT    |         |
|  +--------------++--------------++--------------+         |
|  | [x] Traces   || [x] Traces   || [x] Traces   |         |
|  | [x] Metrics  || [ ] Metrics  || [ ] Metrics  |         |
|  | (Recommended)||              ||              |         |
|  +-------+------++------+-------++------+-------+         |
+----------+-------------+---------------+-----------------+
                         |
               +---------+---------+
               |   OpenTelemetry   |
               |   SpanExporter    |
               +---------+---------+
                         |
       +---------+-------+-------+---------+
       v         v               v         v
   +--------++--------+ +--------++--------+
   |Langfuse|| Zipkin | |  OTLP  || Custom |
   +--------++--------+ +--------++--------+
```

---

## 3. Maven Dependencies and Configuration

### Main Dependencies

```xml
<!-- Spring Boot Auto-configuration -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-autoconfigure</artifactId>
</dependency>

<!-- Embabel Agent (provided by the user) -->
<dependency>
    <groupId>com.embabel.agent</groupId>
    <artifactId>embabel-agent-starter</artifactId>
    <version>${embabel-agent.version}</version>
    <scope>provided</scope>
</dependency>

<!-- Spring AI (for ChatModelObservationFilter) -->
<dependency>
    <groupId>org.springframework.ai</groupId>
    <artifactId>spring-ai-model</artifactId>
    <scope>provided</scope>
</dependency>

<!-- OpenTelemetry Core -->
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-api</artifactId>
</dependency>
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-sdk</artifactId>
</dependency>

<!-- Micrometer Tracing Bridge to OpenTelemetry -->
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-otel</artifactId>
</dependency>
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing</artifactId>
</dependency>

<!-- Spring Boot Actuator -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-actuator</artifactId>
</dependency>
```

### Versions Used

| Component | Version |
|-----------|---------|
| Java | 21 |
| Spring Boot | 3.5.9 |
| Spring AI | 1.1.1 |
| Embabel Agent | 0.3.2-SNAPSHOT |
| OpenTelemetry | 2.17.0 |

---

## 4. Configuration Properties

### Class: `ObservabilityProperties`

**File**: `src/main/java/com/embabel/agent/observability/ObservabilityProperties.java`

**Prefix**: `embabel.observability`

### Available Properties

| Property | Type | Default | Description |
|----------|------|---------|-------------|
| `enabled` | boolean | `true` | Enable/disable all observability |
| `implementation` | enum | `SPRING_OBSERVATION` | Implementation type |
| `service-name` | String | `embabel-agent` | Service name in traces |
| `tracer-name` | String | `embabel-agent` | Instrumentation name |
| `tracer-version` | String | `0.3.2` | Instrumentation version |
| `max-attribute-length` | int | `4000` | Max length before truncation |
| `trace-agent-events` | boolean | `true` | Trace agent events |
| `trace-tool-calls` | boolean | `true` | Trace tool calls |
| `trace-llm-calls` | boolean | `true` | Trace LLM calls (Spring AI) |
| `trace-planning` | boolean | `true` | Trace planning events |
| `trace-state-transitions` | boolean | `true` | Trace state transitions |
| `trace-lifecycle-states` | boolean | `true` | Trace WAITING/PAUSED/STUCK |
| `trace-object-binding` | boolean | `false` | Trace object binding (verbose) |

### Implementation Types

```java
public enum Implementation {
    /**
     * Uses Spring Observation API with custom handler.
     * - Both Traces AND Metrics generated automatically
     * - Integrated with Spring Boot and Spring AI
     * - RECOMMENDED for complete observability
     */
    SPRING_OBSERVATION,

    /**
     * Uses Micrometer Tracing API directly.
     * - Traces only (no automatic metrics)
     * - Integrated with Spring AI
     */
    MICROMETER_TRACING,

    /**
     * Uses OpenTelemetry API directly.
     * - Full control over spans
     * - Traces only
     */
    OPENTELEMETRY_DIRECT
}
```

### Configuration Example

```yaml
embabel:
  observability:
    enabled: true
    service-name: my-agent-application
    implementation: SPRING_OBSERVATION
    trace-agent-events: true
    trace-tool-calls: true
    trace-llm-calls: true
    trace-planning: true
    trace-state-transitions: true
    trace-lifecycle-states: true
    trace-object-binding: false
    max-attribute-length: 4000
```

---

## 5. The Three Tracing Implementations

### 5.1 SPRING_OBSERVATION (Recommended)

**Class**: `EmbabelFullObservationEventListener`

**File**: `src/main/java/com/embabel/agent/observability/observation/EmbabelFullObservationEventListener.java`

#### Characteristics

| Feature | Support |
|---------|---------|
| Traces | YES |
| Metrics | YES (automatic) |
| Spring AI Integration | YES |
| Root Spans | YES |

#### How It Works

```
Embabel Events --> EmbabelFullObservationEventListener
                            |
                            v
                    Creates EmbabelObservationContext
                            |
                            v
                    Observation API (ObservationRegistry)
                            |
              +-------------+-------------+
              |                           |
              v                           v
    EmbabelTracing              DefaultMeter
    ObservationHandler          ObservationHandler
              |                           |
              v                           v
           Traces                     Metrics
```

#### Main Methods

| Method | Description |
|--------|-------------|
| `onAgentProcessCreation()` | Starts a span for a new agent |
| `onAgentProcessCompleted()` | Ends the span successfully |
| `onAgentProcessFailed()` | Ends the span with error |
| `onActionStart()` | Starts a child span for an action |
| `onActionResult()` | Ends the action span |
| `onToolCallRequest()` | Starts a span for a tool call |
| `onToolCallResponse()` | Ends the tool span |
| `onGoalAchieved()` | Creates an instant span for the goal |
| `onPlanFormulated()` | Creates a span for the plan |
| `onStateTransition()` | Creates a span for transitions |

---

### 5.2 MICROMETER_TRACING

**Class**: `EmbabelSpringObservationEventListener`

**File**: `src/main/java/com/embabel/agent/observability/observation/EmbabelSpringObservationEventListener.java`

#### Characteristics

| Feature | Support |
|---------|---------|
| Traces | YES |
| Metrics | NO |
| Spring AI Integration | YES |
| Root Spans | YES |

#### How It Works

This implementation uses the Micrometer `Tracer` API directly:

```java
// Creating a root span (new trace)
private Span createRootSpan(String name) {
    try (Tracer.SpanInScope ignored = tracer.withSpan(null)) {
        return tracer.nextSpan().name(name);
    }
}

// Setting in thread-local context (critical for Spring AI)
span.start();
Tracer.SpanInScope scope = tracer.withSpan(span);
```

**Key Point**: `tracer.withSpan(span)` places the span in the thread-local context, allowing Spring AI to find the parent via `tracer.currentSpan()`.

---

### 5.3 OPENTELEMETRY_DIRECT

**Class**: `EmbabelObservationEventListener`

**File**: `src/main/java/com/embabel/agent/observability/observation/EmbabelObservationEventListener.java`

#### Characteristics

| Feature | Support |
|---------|---------|
| Traces | YES |
| Metrics | NO |
| Spring AI Integration | YES |
| Root Spans | YES |
| Full Control | YES |

#### How It Works

Uses the OpenTelemetry API directly:

```java
// Creating a root span with Context.root()
Span span = tracer.spanBuilder(agentName)
        .setParent(Context.root())  // New trace
        .setSpanKind(SpanKind.SERVER)
        .setAttribute("embabel.agent.name", agentName)
        .startSpan();

// Setting in context (critical)
Scope scope = span.makeCurrent();
```

**Key Point**: `span.makeCurrent()` puts the span into `io.opentelemetry.context.Context.current()`, which allows Spring AI to find the parent.

---

## 6. Class Details

### 6.1 ObservabilityAutoConfiguration

**File**: `src/main/java/com/embabel/agent/observability/ObservabilityAutoConfiguration.java`

**Role**: Main auto-configuration that creates observability beans.

#### Created Beans

| Bean | Condition | Description |
|------|-----------|-------------|
| `embabelObservationEventListener` | `trace-agent-events=true` | Embabel event listener |
| `chatModelObservationFilter` | `trace-llm-calls=true` | Filter for Spring AI |

#### Selection Logic

```java
@Bean
public AgenticEventListener embabelObservationEventListener(...) {
    // 1. Try SPRING_OBSERVATION
    if (implementation == SPRING_OBSERVATION && observationRegistry != null) {
        return new EmbabelFullObservationEventListener(observationRegistry, properties);
    }

    // 2. Fallback to MICROMETER_TRACING
    if (tracer != null) {
        return new EmbabelSpringObservationEventListener(tracer, properties);
    }

    // 3. Fallback to OPENTELEMETRY_DIRECT
    return new EmbabelObservationEventListener(openTelemetryProvider, properties);
}
```

---

### 6.2 OpenTelemetrySdkAutoConfiguration

**File**: `src/main/java/com/embabel/agent/observability/OpenTelemetrySdkAutoConfiguration.java`

**Role**: Configures the OpenTelemetry SDK with multi-exporter support.

#### Created Beans

| Bean | Description |
|------|-------------|
| `openTelemetryResource` | Resource with service name |
| `sdkTracerProvider` | Trace provider with all exporters |
| `openTelemetry` | OpenTelemetry SDK instance |

#### Multi-Exporter Support

```java
@Bean
public SdkTracerProvider sdkTracerProvider(
        ObjectProvider<List<SpanExporter>> exportersProvider,
        Resource resource) {

    // Collect all SpanExporter beans
    List<SpanExporter> exporters = exportersProvider.getIfAvailable();

    SdkTracerProviderBuilder builder = SdkTracerProvider.builder()
            .setResource(resource);

    // Add each exporter as BatchSpanProcessor
    for (SpanExporter exporter : exporters) {
        builder.addSpanProcessor(BatchSpanProcessor.builder(exporter).build());
    }

    return builder.build();
}
```

---

### 6.3 MicrometerTracingAutoConfiguration

**File**: `src/main/java/com/embabel/agent/observability/MicrometerTracingAutoConfiguration.java`

**Role**: Configures the Micrometer to OpenTelemetry bridge.

#### Created Beans

| Bean | Description |
|------|-------------|
| `otelCurrentTraceContext` | Trace context for propagation |
| `embabelTracingObservationCustomizer` | Registers the custom handler |

#### Handler Registration

```java
@Bean
public ObservationRegistryCustomizer<ObservationRegistry> embabelTracingObservationCustomizer(...) {
    return registry -> {
        EmbabelTracingObservationHandler handler =
            new EmbabelTracingObservationHandler(tracer, otelTracer);
        registry.observationConfig().observationHandler(handler);
    };
}
```

---

### 6.4 EmbabelObservationContext

**File**: `src/main/java/com/embabel/agent/observability/observation/EmbabelObservationContext.java`

**Role**: Custom context for Embabel observations.

#### Event Types

```java
public enum EventType {
    AGENT_PROCESS,      // Agent process (root or sub)
    ACTION,             // Action execution
    GOAL,               // Goal achievement
    TOOL_CALL,          // Tool call
    PLANNING,           // Planning event
    STATE_TRANSITION,   // State transition
    LIFECYCLE           // Lifecycle state (WAITING, PAUSED, STUCK)
}
```

#### Factory Methods

| Method | Description |
|--------|-------------|
| `rootAgent(runId, name)` | Root agent (new trace) |
| `subAgent(runId, name, parentRunId)` | Sub-agent |
| `action(runId, actionName)` | Action |
| `goal(runId, goalName)` | Goal |
| `toolCall(runId, toolName)` | Tool call |
| `planning(runId, planningName)` | Planning |
| `stateTransition(runId, stateName)` | Transition |
| `lifecycle(runId, lifecycleState)` | Lifecycle |

---

### 6.5 EmbabelTracingObservationHandler

**File**: `src/main/java/com/embabel/agent/observability/observation/EmbabelTracingObservationHandler.java`

**Role**: Custom handler that intercepts Embabel observations.

#### Key Features

1. **Root span creation** for root agents
2. **Hierarchy resolution** via `runId`/`parentRunId`
3. **Spring AI integration** via `tracer.withSpan()`

#### Parent Resolution

```java
private Span resolveParentSpan(EmbabelObservationContext context) {
    String runId = context.getRunId();

    switch (context.getEventType()) {
        case ACTION:
            // Actions are children of their agent
            return activeAgentSpans.get(runId);

        case AGENT_PROCESS:
            // Sub-agents are children of their parent
            if (context.getParentRunId() != null) {
                return activeAgentSpans.get(context.getParentRunId());
            }
            return null;

        case TOOL_CALL:
        case GOAL:
        case PLANNING:
        case STATE_TRANSITION:
        case LIFECYCLE:
            // Children of current action, or agent otherwise
            Span actionSpan = activeActionSpans.get(runId);
            return actionSpan != null ? actionSpan : activeAgentSpans.get(runId);

        default:
            return null;
    }
}
```

---

### 6.6 ChatModelObservationFilter

**File**: `src/main/java/com/embabel/agent/observability/observation/ChatModelObservationFilter.java`

**Role**: Enriches Spring AI observations with prompt and completion.

#### Added Attributes

| Attribute | Description |
|-----------|-------------|
| `gen_ai.prompt` | The prompt sent to the LLM |
| `gen_ai.completion` | The LLM response |
| `input.value` | OpenTelemetry convention (prompt) |
| `output.value` | OpenTelemetry convention (completion) |

#### Prompt Extraction

```java
private String extractPrompt(ChatModelObservationContext chatContext) {
    var instructions = chatContext.getRequest().getInstructions();

    StringBuilder sb = new StringBuilder();
    for (var message : instructions) {
        sb.append("[").append(message.getMessageType()).append("]: ");
        sb.append(message.getText()).append("\n");
    }
    return sb.toString();
}
```

---

## 7. Integration with Spring AI

### How Does It Work?

Integration with Spring AI is **critical** for LLM calls to appear as **children** of Embabel spans.

#### Mechanism

1. **Embabel span activated**: When an action starts, its span is placed in the thread-local context
2. **Spring AI detects the parent**: When the ChatModel is called, Spring AI looks for the parent span in the context
3. **LLM span created as child**: The LLM span inherits the action span as parent

#### Critical Code

```java
// In EmbabelSpringObservationEventListener
span.start();
Tracer.SpanInScope scope = tracer.withSpan(span);  // CRITICAL!

// In EmbabelObservationEventListener (OpenTelemetry)
Scope scope = span.makeCurrent();  // CRITICAL!
```

### Resulting Hierarchy

```
Agent Root (trace root)
+-- Action 1
|   +-- LLM Call (Spring AI - child of Action 1)
|   |   +-- OpenAI API Call
|   +-- Tool Call A
|   +-- Tool Call B
+-- Planning Event
+-- Goal Achieved
+-- Action 2
    +-- LLM Call (Spring AI)
    +-- Tool Call C
```

---

## 8. Implemented Features for Embabel

### [OK] Agent Event Tracing

| Event | Span | Attributes |
|-------|------|------------|
| `AgentProcessCreationEvent` | Agent name | runId, goal, planner, input |
| `AgentProcessCompletedEvent` | (same) | status=completed, output, result |
| `AgentProcessFailedEvent` | (same) | status=failed, error |

### [OK] Action Tracing

| Event | Span | Attributes |
|-------|------|------------|
| `ActionExecutionStartEvent` | Action shortName | name, description, input |
| `ActionExecutionResultEvent` | (same) | status, duration_ms, output, result |

### [OK] Tool Tracing

| Event | Span | Attributes |
|-------|------|------------|
| `ToolCallRequestEvent` | tool:ToolName | tool_name, input |
| `ToolCallResponseEvent` | (same) | duration_ms, output |

### [OK] Goal Tracing

| Event | Span | Attributes |
|-------|------|------------|
| `GoalAchievedEvent` | goal:GoalName | goal_name, world_state |

### [OK] Planning Tracing

| Event | Span | Attributes |
|-------|------|------------|
| `AgentProcessReadyToPlanEvent` | planning:ready | world_state |
| `AgentProcessPlanFormulatedEvent` | planning:formulated | iteration, actions_count, goal |

### [OK] State Transition Tracing

| Event | Span | Attributes |
|-------|------|------------|
| `StateTransitionEvent` | state:StateName | state_to |

### [OK] Lifecycle State Tracing

| Event | Span | Attributes |
|-------|------|------------|
| `AgentProcessWaitingEvent` | lifecycle:waiting | state |
| `AgentProcessPausedEvent` | lifecycle:paused | state |
| `AgentProcessStuckEvent` | lifecycle:stuck | state |

### [OK] Object Binding Tracing (optional)

| Event | Span | Attributes |
|-------|------|------------|
| `ObjectAddedEvent` | object:added | object_type |
| `ObjectBoundEvent` | object:bound | object_name, object_type |

### [OK] LLM Call Tracing (Spring AI)

| Feature | Description |
|---------|-------------|
| `ChatModelObservationFilter` | Enriches with prompt/completion |
| Parent-child hierarchy | LLM calls are children of actions |

---

## 9. Traces, Metrics, and Observability Compatibility

### Compatibility Table

| Feature | SPRING_OBSERVATION | MICROMETER_TRACING | OPENTELEMETRY_DIRECT |
|---------|--------------------|--------------------|----------------------|
| **Distributed Traces** | YES | YES | YES |
| **Automatic Metrics** | YES | NO | NO |
| **Spring AI Integration** | YES | YES | YES |
| **Root Spans** | YES | YES | YES |
| **Context Propagation** | YES | YES | YES |
| **Multi-Exporter** | YES | YES | YES |
| **Langfuse** | YES | YES | YES |
| **Zipkin** | YES | YES | YES |
| **OTLP** | YES | YES | YES |

### Generated Metrics (SPRING_OBSERVATION only)

With the `SPRING_OBSERVATION` implementation, Spring Boot automatically generates metrics:

- **Timer**: Duration of each observation
- **Counter**: Number of executions
- **LongTaskTimer**: For long-running tasks

These metrics are accessible via Spring Boot Actuator (`/actuator/metrics`).

### Standard Span Attributes

| Attribute | Example Value |
|-----------|---------------|
| `embabel.agent.name` | MyAgent |
| `embabel.agent.run_id` | abc-123 |
| `embabel.agent.goal` | ProcessUserRequest |
| `embabel.agent.is_subagent` | false |
| `embabel.agent.parent_id` | parent-456 |
| `embabel.agent.planner_type` | GOAP |
| `embabel.agent.status` | completed |
| `embabel.action.name` | MyAction |
| `embabel.action.short_name` | myAction |
| `embabel.action.duration_ms` | 1234 |
| `embabel.tool.name` | searchTool |
| `embabel.tool.input` | {query: "..."} |
| `embabel.tool.output` | {...} |
| `embabel.goal.name` | ProcessedState |
| `embabel.plan.iteration` | 1 |
| `embabel.plan.actions_count` | 3 |
| `embabel.state.to` | FinalState |
| `embabel.lifecycle.state` | WAITING |
| `input.value` | (input content) |
| `output.value` | (output content) |

---

## 10. Usage Guide

### Step 1: Add the Dependency

```xml
<dependency>
    <groupId>com.embabel.agent</groupId>
    <artifactId>embabel-agent-observability</artifactId>
    <version>0.3.2-SNAPSHOT</version>
</dependency>
```

### Step 2: Add an Exporter

#### Option A: Langfuse

```xml
<dependency>
    <groupId>com.quantpulsar</groupId>
    <artifactId>opentelemetry-exporter-langfuse</artifactId>
    <version>0.3.2-SNAPSHOT</version>
</dependency>
```

```yaml
langfuse:
  public-key: pk-lf-...
  secret-key: sk-lf-...
  host: https://cloud.langfuse.com
```

#### Option B: Zipkin

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-zipkin</artifactId>
</dependency>
```

```yaml
management:
  zipkin:
    tracing:
      endpoint: http://localhost:9411/api/v2/spans
```

#### OTLP (Jaeger, etc.)

```xml
<dependency>
    <groupId>io.opentelemetry</groupId>
    <artifactId>opentelemetry-exporter-otlp</artifactId>
</dependency>
```

```yaml
management:
  otlp:
    tracing:
      endpoint: http://localhost:4317
```

### Step 3: Configure

```yaml
embabel:
  observability:
    enabled: true
    service-name: my-application
    implementation: SPRING_OBSERVATION
    trace-agent-events: true
    trace-tool-calls: true
    trace-llm-calls: true
```

### Step 4: That's It!

Auto-configuration handles the rest. Your Embabel agents are now traced automatically.

---

## 11. Flow Examples

### Flow 1: Creating a Root Agent

```
1. User calls the agent
   |
   v
2. AgentProcessCreationEvent emitted
   |
   v
3. EmbabelFullObservationEventListener.onAgentProcessCreation()
   |
   v
4. Creates EmbabelObservationContext(isRoot=true, runId="abc-123")
   |
   v
5. Creates Observation with context
   |
   v
6. observation.start() --> EmbabelTracingObservationHandler.onStart()
   |
   v
7. Handler detects isRoot=true
   |
   v
8. Creates span with Context.root() (new trace)
   |
   v
9. Span is active in thread-local context
   |
   v
10. All Spring AI LLM calls see this span as parent
```

### Flow 2: Action with LLM Call

```
1. ActionExecutionStartEvent
   |
   v
2. Creates action observation (child of agent)
   |
   v
3. observation.start() and observation.openScope()
   |
   v
4. Action span active in thread-local
   |
   v
5. Spring AI ChatModel called
   |
   v
6. ChatModel sees action span in context
   |
   v
7. Creates LLM observation as child of action
   |
   v
8. ChatModelObservationFilter enriches with prompt/completion
   |
   v
9. LLM span created as child of action span
   |
   v
10. ActionExecutionResultEvent
    |
    v
11. Closes action scope and stops observation
```

### Flow 3: Replanning

```
1. First plan formulated
   |
   v
2. planIterations[runId] = 1
   |
   v
3. Creates span "planning:formulated"
   |
   v
4. (Execution fails, replanning needed)
   |
   v
5. New plan formulated
   |
   v
6. planIterations[runId] = 2
   |
   v
7. Creates span "planning:replanning" (iteration=2)
```

---

## 12. Supported Exporters

### Langfuse

**Module**: `embabel-agent-observability-langfuse`

**Specific Attributes**:
- `langfuse.span.name`: Name displayed in Langfuse
- `langfuse.trace.name`: Trace name
- `langfuse.observation.type`: Type (agent, span, tool, event)
- `langfuse.level`: Level (WARNING for replanning/stuck)

### Zipkin

**Configuration**: Via Spring Boot Actuator

```yaml
management:
  zipkin:
    tracing:
      endpoint: http://localhost:9411/api/v2/spans
```

### OTLP (Jaeger, Grafana Tempo, etc.)

**Configuration**: Via Spring Boot Actuator

```yaml
management:
  otlp:
    tracing:
      endpoint: http://localhost:4317
```

### Custom

Create a `SpanExporter` bean:

```java
@Bean
public SpanExporter customExporter() {
    return new MyCustomSpanExporter();
}
```

It will be automatically detected and added to the `SdkTracerProvider`.

---

## 13. Roadmap - Implemented vs Available Features

This section documents the implementation status of observability features based on events available in **embabel-agent**.

### 13.1 Legend

| Status | Description |
|--------|-------------|
| [IMPL] | Implemented and functional |
| [PART] | Partially implemented |
| [TODO] | Available in embabel-agent but not yet traced |
| [FUTURE] | Potential feature to develop |

---

### 13.2 Agent Process Events (AgentProcessEvent)

| Event | Tracing | Metrics | Status | Notes |
|-------|---------|---------|--------|-------|
| `AgentProcessCreationEvent` | YES | YES | [IMPL] | Creates the trace root span |
| `AgentProcessCompletedEvent` | YES | YES | [IMPL] | Closes span with status=completed |
| `AgentProcessFailedEvent` | YES | YES | [IMPL] | Closes span with status=failed + error |
| `AgentProcessWaitingEvent` | YES | YES | [IMPL] | Configurable via `trace-lifecycle-states` |
| `AgentProcessPausedEvent` | YES | YES | [IMPL] | Configurable via `trace-lifecycle-states` |
| `AgentProcessStuckEvent` | YES | YES | [IMPL] | Configurable via `trace-lifecycle-states` |
| `ProcessKilledEvent` | NO | NO | [TODO] | Event available, not yet traced |
| `ProgressUpdateEvent` | NO | NO | [TODO] | Event available, not yet traced |

---

### 13.3 Planning Events

| Event | Tracing | Metrics | Status | Notes |
|-------|---------|---------|--------|-------|
| `AgentProcessReadyToPlanEvent` | YES | YES | [IMPL] | Span "planning:ready" |
| `AgentProcessPlanFormulatedEvent` | YES | YES | [IMPL] | Span "planning:formulated" or "planning:replanning" |

**Traced Attributes:**
- `embabel.plan.iteration` - Iteration number
- `embabel.plan.actions_count` - Number of planned actions
- `embabel.plan.is_replanning` - Replanning indicator
- `embabel.plan.goal` - Plan goal

---

### 13.4 Action Events

| Event | Tracing | Metrics | Status | Notes |
|-------|---------|---------|--------|-------|
| `ActionExecutionStartEvent` | YES | YES | [IMPL] | Opens action span |
| `ActionExecutionResultEvent` | YES | YES | [IMPL] | Closes span with duration and status |

**Traced Attributes:**
- `embabel.action.name` - Full name
- `embabel.action.short_name` - Short name
- `embabel.action.description` - Description
- `embabel.action.duration_ms` - Duration in milliseconds
- `embabel.action.status` - Final status
- `embabel.action.result` - Result (if enabled)

---

### 13.5 Tool Events

| Event | Tracing | Metrics | Status | Notes |
|-------|---------|---------|--------|-------|
| `ToolCallRequestEvent` | YES | YES | [IMPL] | Configurable via `trace-tool-calls` |
| `ToolCallResponseEvent` | YES | YES | [IMPL] | Includes duration and success/failure |

**Traced Attributes:**
- `embabel.tool.name` - Tool name
- `embabel.tool.input` - Input (truncated)
- `embabel.tool.output` - Output (truncated)
- `embabel.tool.duration_ms` - Duration in milliseconds
- `embabel.tool.correlation_id` - Correlation ID

**Note:** Tools also benefit from `ObservabilityToolCallback` in embabel-agent for additional Micrometer observations.

---

### 13.6 Goal Events

| Event | Tracing | Metrics | Status | Notes |
|-------|---------|---------|--------|-------|
| `GoalAchievedEvent` | YES | YES | [IMPL] | Span "goal:achieved" |

**Traced Attributes:**
- `embabel.goal.name` - Full goal name
- `embabel.goal.short_name` - Short name

---

### 13.7 State Machine Events

| Event | Tracing | Metrics | Status | Notes |
|-------|---------|---------|--------|-------|
| `StateTransitionEvent` | YES | YES | [IMPL] | Configurable via `trace-state-transitions` |

**Traced Attributes:**
- `embabel.state.to` - New state

---

### 13.8 Object Binding Events

| Event | Tracing | Metrics | Status | Notes |
|-------|---------|---------|--------|-------|
| `ObjectAddedEvent` | YES | YES | [IMPL] | Configurable via `trace-object-binding` (default: OFF) |
| `ObjectBoundEvent` | YES | YES | [IMPL] | Configurable via `trace-object-binding` (default: OFF) |

**Note:** These events are disabled by default as they can be very verbose.

---

### 13.9 LLM Events (Spring AI)

| Event | Tracing | Metrics | Status | Notes |
|-------|---------|---------|--------|-------|
| `LlmRequestEvent` | INDIRECT | NO | [PART] | Traced via Spring AI ChatModel |
| `LlmResponseEvent` | INDIRECT | NO | [PART] | Traced via Spring AI ChatModel |
| `ChatModelCallEvent` | YES | YES | [IMPL] | Via `ChatModelObservationFilter` |

**Traced Attributes:**
- `gen_ai.prompt` - Prompt sent to LLM
- `gen_ai.completion` - LLM response
- `input.value` / `output.value` - OpenTelemetry GenAI conventions

---

### 13.10 Platform Events (NOT IMPLEMENTED)

These events are available in embabel-agent but **not yet traced**:

| Event | Description | Status | Priority |
|-------|-------------|--------|----------|
| `AgentDeploymentEvent` | Agent deployment | [TODO] | Medium |
| `DynamicAgentCreationEvent` | Dynamic agent creation | [TODO] | High |
| `RankingChoiceRequestEvent` | Choice/ranking request | [TODO] | Low |
| `RankingChoiceMadeEvent` | Choice made | [TODO] | Low |
| `RankingChoiceCouldNotBeMadeEvent` | Choice failure | [TODO] | Low |

**Suggested Implementation:**
```java
// In AgenticEventListener
@Override
public void onPlatformEvent(AgentPlatformEvent event) {
    if (event instanceof AgentDeploymentEvent deployment) {
        // Create span "platform:deployment"
    } else if (event instanceof DynamicAgentCreationEvent creation) {
        // Create span "platform:dynamic-agent-creation"
    }
    // etc.
}
```

---

### 13.11 RAG Events (NOT IMPLEMENTED)

These RAG events are available in `embabel-agent-rag` but **not yet traced**:

| Event | Description | Status | Priority |
|-------|-------------|--------|----------|
| `RagRequestReceivedEvent` | RAG request received | [TODO] | High |
| `RagResponseEvent` | RAG response | [TODO] | High |
| `AgentProcessRagEvent` | RAG in agent context | [TODO] | High |
| `InitialRequestRagPipelineEvent` | Start of RAG pipeline | [TODO] | Medium |
| `InitialResponseRagPipelineEvent` | Initial pipeline response | [TODO] | Medium |
| `EnhancementStartingRagPipelineEvent` | Start of enhancement | [TODO] | Low |
| `EnhancementCompletedRagPipelineEvent` | End of enhancement | [TODO] | Low |

**Note:** RAG tracing would require a new `RagEventListener` listener or an extension of the existing listener.

---

### 13.12 Available Metrics

#### Implemented Metrics (SPRING_OBSERVATION only)

| Metric | Type | Description |
|--------|------|-------------|
| `embabel.agent.process.*` | Timer | Agent process duration |
| `embabel.action.*` | Timer | Action duration |
| `embabel.tool.*` | Timer | Tool call duration |
| `embabel.goal.*` | Counter | Number of goals achieved |
| `embabel.planning.*` | Timer | Planning duration |

#### Metrics via ObservabilityToolCallback (embabel-agent)

| Metric | Type | Description |
|--------|------|-------------|
| `tool.call` | Timer | Duration by tool |
| `tool.call.error` | Counter | Errors by tool |

#### Metrics via AgenticEventListenerToolsStats (embabel-agent)

| Metric | Type | Description |
|--------|------|-------------|
| `toolsStats.calls` | Counter | Number of calls by tool |
| `toolsStats.failures` | Counter | Failures by tool |
| `toolsStats.averageResponseTime` | Gauge | Average time by tool |

---

### 13.13 Future Metrics (NOT IMPLEMENTED)

| Metric | Type | Description | Priority |
|--------|------|-------------|----------|
| `embabel.llm.tokens.input` | Counter | LLM input tokens | High |
| `embabel.llm.tokens.output` | Counter | LLM output tokens | High |
| `embabel.llm.cost` | Gauge | Estimated cost per LLM call | High |
| `embabel.agent.cost.total` | Gauge | Total cost per agent execution | High |
| `embabel.rag.latency` | Timer | RAG call latency | Medium |
| `embabel.rag.documents.retrieved` | Counter | Documents retrieved | Medium |
| `embabel.platform.agents.deployed` | Gauge | Deployed agents | Low |
| `embabel.platform.agents.active` | Gauge | Active agents | Low |

---

### 13.14 Roadmap Summary

#### Phase 1 - Current (v0.3.x) [IMPL]

```
+------------------------------------------+
|         IMPLEMENTED FEATURES             |
+------------------------------------------+
| [OK] Agent Process Events (6/8)          |
| [OK] Planning Events (2/2)               |
| [OK] Action Events (2/2)                 |
| [OK] Tool Events (2/2)                   |
| [OK] Goal Events (1/1)                   |
| [OK] State Transition Events (1/1)       |
| [OK] Object Binding Events (2/2)         |
| [OK] LLM Events via ChatModel (1/1)      |
| [OK] 3 Tracing Backends                  |
| [OK] Automatic Metrics (Spring Obs)      |
| [OK] Multi-Exporters                     |
+------------------------------------------+
```

#### Phase 2 - Short Term (v0.4.x) [TODO]

```
+------------------------------------------+
|        FEATURES TO IMPLEMENT             |
+------------------------------------------+
| [ ] ProcessKilledEvent tracing           |
| [ ] ProgressUpdateEvent tracing          |
| [ ] DynamicAgentCreationEvent tracing    |
| [ ] LLM Token Usage Metrics              |
| [ ] LLM Cost Estimation                  |
+------------------------------------------+
```

#### Phase 3 - Medium Term (v0.5.x) [TODO]

```
+------------------------------------------+
|          RAG FEATURES                    |
+------------------------------------------+
| [ ] RagEventListener implementation      |
| [ ] RagRequestReceivedEvent tracing      |
| [ ] RagResponseEvent tracing             |
| [ ] RAG Pipeline Events tracing          |
| [ ] RAG Metrics (latency, documents)     |
+------------------------------------------+
```

#### Phase 4 - Long Term (v1.0.x) [FUTURE]

```
+------------------------------------------+
|       ADVANCED FEATURES                  |
+------------------------------------------+
| [ ] Platform Events tracing              |
| [ ] Ranking Events tracing               |
| [ ] Pre-configured Dashboard (Grafana)   |
| [ ] Automatic Alerts                     |
| [ ] Performance Profiling                |
| [ ] Cost Analytics Dashboard             |
+------------------------------------------+
```

---

### 13.15 Embabel-Agent Event Classes (Reference)

#### Source Files in embabel-agent-api

| File | Defined Events |
|------|----------------|
| `AgenticEvent.kt` | Root interface `AgenticEvent` |
| `AgentProcessEvent.kt` | All `AgentProcessEvent` (lifecycle, planning, execution) |
| `AgentPlatformEvent.kt` | All `AgentPlatformEvent` (deployment, ranking) |
| `AgenticEventListener.kt` | Interface `AgenticEventListener` with `onPlatformEvent()` and `onProcessEvent()` |

#### Source Files in embabel-agent-rag

| File | Defined Events |
|------|----------------|
| `RagEvent.kt` | `RagEvent`, `RagRequestReceivedEvent`, `RagResponseEvent`, `RagEventListener` |
| `PipelineRagEvents.kt` | `RagPipelineEvent`, `InitialRequestRagPipelineEvent`, etc. |

---

---

## 14. Complete Reference of Available Events

This section exhaustively documents all events available in the Embabel Agent ecosystem, their location in the source code, and their observability interest.

### 14.1 Agent Process Events

**Source File**: `embabel-agent-api/src/main/kotlin/com/embabel/agent/api/event/AgentProcessEvent.kt`

| Event | Description | Observability Interest |
|-------|-------------|------------------------|
| `AgentProcessCreationEvent` | Agent process creation | **Trace**: root span of the complete trace |
| `AgentProcessPlanFormulatedEvent` | Plan generated by planner | **Trace + Metric**: planning duration |
| `ActionExecutionStartEvent` | Start of action execution | **Trace**: span per action |
| `ActionExecutionResultEvent` | Action execution result | **Metric**: duration, success/failure |
| `ToolCallRequestEvent` | Tool call request | **Trace**: span for the tool |
| `ToolCallResponseEvent` | Tool call response | **Metric**: latency, success/failure |
| `LlmRequestEvent` | Request to an LLM | **Trace**: LLM span |
| `LlmResponseEvent` | Response from an LLM | **Metric**: tokens, latency |
| `GoalAchievedEvent` | Goal achieved | **Metric**: success rate |
| `AgentProcessCompletedEvent` | Process completed successfully | **Metric**: global success rate |
| `AgentProcessFailedEvent` | Process failed | **Metric**: failure rate |
| `StateTransitionEvent` | State transition in workflow | **Trace**: workflow tracking |

---

### 14.2 Platform Events

**Source File**: `embabel-agent-api/src/main/kotlin/com/embabel/agent/api/event/AgentPlatformEvent.kt`

| Event | Description | Observability Interest |
|-------|-------------|------------------------|
| `AgentDeploymentEvent` | New agent deployment | **Metric**: deployment counter |
| `RankingChoiceMadeEvent` | Ranking choice made | **Trace + Metric**: ranking decisions |
| `DynamicAgentCreationEvent` | Dynamic agent creation | **Trace**: span for dynamic creation |

---

### 14.3 RAG Events

**Source File**: `embabel-agent-rag/embabel-agent-rag-pipeline/src/main/kotlin/com/embabel/agent/rag/pipeline/event/PipelineRagEvents.kt`

| Event | Description | Observability Interest |
|-------|-------------|------------------------|
| `InitialRequestRagPipelineEvent` | Initial RAG request | **Trace**: root span of RAG pipeline |
| `InitialResponseRagPipelineEvent` | Initial RAG response | **Metric**: retrieval latency |
| `EnhancementStartingRagPipelineEvent` | Start of enhancement | **Trace**: enhancement span |
| `EnhancementCompletedRagPipelineEvent` | End of enhancement | **Metric**: enhancement duration |

---

### 14.4 Output Channel Events

**Source File**: `embabel-agent-api/src/main/kotlin/com/embabel/agent/api/channel/OutputChannel.kt`

| Event | Description | Observability Interest |
|-------|-------------|------------------------|
| `MessageOutputChannelEvent` | Chat messages sent | **Metric**: message counter |
| `LoggingOutputChannelEvent` | Logs with level (INFO, WARN, ERROR) | **Metric**: counter by log level |
| `ProgressOutputChannelEvent` | Progress update | **Trace**: progress attribute |

---

### 14.5 Key Operations to Instrument

#### Planning

**Source File**: `embabel-agent-api/src/main/kotlin/com/embabel/plan/Planner.kt`

| Operation | Type | Description |
|-----------|------|-------------|
| `worldState()` | **Metric** | Duration of world state calculation |
| `planToGoal()` | **Trace** | Span for planning operation |
| `bestValuePlanToAnyGoal()` | **Metric** | Number of plans evaluated |

#### LLM Operations

**Source File**: `ChatClientLlmOperations.kt`

| Operation | Type | Description |
|-----------|------|-------------|
| Response time | **Histogram** | Duration by model and provider |
| Tokens (input/output) | **Counter** | Count by model and direction |
| Error rate | **Counter** | Errors by provider |

---

### 14.6 Recommended Metrics

This section defines recommended metrics for complete observability of Embabel agents.

#### Agent Process Metrics

| Metric | Type | Suggested Labels |
|--------|------|------------------|
| `agent.process.duration` | Histogram | `agent_name`, `goal`, `status` |
| `agent.action.duration` | Histogram | `action_name`, `status` |
| `agent.tool.calls` | Counter | `tool_name`, `status` |

#### LLM Metrics

| Metric | Type | Suggested Labels |
|--------|------|------------------|
| `agent.llm.requests` | Counter | `model`, `provider` |
| `agent.llm.duration` | Histogram | `model`, `interaction_type` |
| `agent.llm.tokens` | Counter | `model`, `direction` (input/output) |

#### Planning & RAG Metrics

| Metric | Type | Suggested Labels |
|--------|------|------------------|
| `agent.plan.duration` | Histogram | `planner_type` |
| `agent.rag.duration` | Histogram | `stage` |
| `agent.goal.achieved` | Counter | `goal_name` |

---

### 14.7 Existing Infrastructure in Embabel-Agent

The embabel-agent application already has partial observability infrastructure:

| Component | Description | Usage |
|-----------|-------------|-------|
| `ObservabilityToolCallback` | Uses Micrometer ObservationRegistry | Tool observations |
| `EventPublishingToolCallback` | Publishes `ToolCallRequestEvent`/`ToolCallResponseEvent` | Event publishing |
| `AgenticEventListenerToolsStats` | Collects stats (count, failures, avg time) | Tool statistics |

---

### 14.8 Implementation Priorities

#### High Priority

- [ ] `LlmRequestEvent` / `LlmResponseEvent` - Token metrics and costs
- [ ] `InitialRequestRagPipelineEvent` / `InitialResponseRagPipelineEvent` - RAG tracing
- [ ] `DynamicAgentCreationEvent` - Dynamic creation tracing
- [ ] Metrics `agent.llm.tokens` and `agent.llm.duration`

#### Medium Priority

- [ ] `AgentDeploymentEvent` - Deployment counter
- [ ] `EnhancementStartingRagPipelineEvent` / `EnhancementCompletedRagPipelineEvent`
- [ ] `MessageOutputChannelEvent` - Message counter
- [ ] Metrics `agent.plan.duration`

#### Low Priority

- [ ] `RankingChoiceMadeEvent` - Ranking decisions
- [ ] `LoggingOutputChannelEvent` - Counter by level
- [ ] `ProgressOutputChannelEvent` - Progress attribute
- [ ] Platform metrics (`agents.deployed`, `agents.active`)

---

## Summary

This project is a **sophisticated observability library** that:

1. **[OK] Translates Embabel Agent events** into OpenTelemetry spans
2. **[OK] Supports three tracing backends** (flexible architecture)
3. **[OK] Integrates with Spring AI** (LLM calls as children)
4. **[OK] Provides automatic metrics** (SPRING_OBSERVATION mode)
5. **[OK] Enables distributed tracing** (root spans, correct hierarchy)
6. **[OK] Supports multiple exporters** (Langfuse, Zipkin, OTLP, custom)
7. **[OK] Highly configurable** (7 on/off event categories)
8. **[OK] Production-ready** (Spring Boot auto-configuration, thread-safe)

**Recommendation**: Use `SPRING_OBSERVATION` to benefit from both **traces** and automatic **metrics**.
