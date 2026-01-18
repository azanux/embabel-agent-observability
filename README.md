# Embabel Agent Observability

[![Java](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5.9-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![OpenTelemetry](https://img.shields.io/badge/OpenTelemetry-2.17.0-blue.svg)](https://opentelemetry.io/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

**Unified observability for Embabel AI Agents** — Automatic tracing, metrics, and LLM call integration with zero code changes.

---

## See It In Action

### Langfuse

![Langfuse Tracing](docs/langfuse.png)

### Zipkin

![Zipkin Tracing](docs/zipkin.png)

---

## Quick Start

> **Note:** This library is not yet published to Maven Central. You need to build and install it locally first:
> ```bash
> git clone https://github.com/azanux/embabel-agent-observability
> cd embabel-agent-observability
> mvn clean install
> ```

### 1. Add the core dependency

```xml
<dependency>
    <groupId>com.embabel.agent</groupId>
    <artifactId>embabel-agent-observability</artifactId>
    <version>0.3.2-SNAPSHOT</version>
</dependency>
```

### 2. Add common configuration

```yaml
# Embabel Observability
embabel:
  observability:
    enabled: true
    service-name: my-agent-app

# Spring Boot Tracing (required)
management:
  tracing:
    enabled: true
    sampling:
      probability: 1.0  # 1.0 = 100% of traces, 0.5 = 50%, etc.
```

### 3. Choose your exporter

<details>
<summary><b>Option A: Langfuse</b> (LLM-focused observability)</summary>

First, clone and install the Langfuse exporter locally:
```bash
git clone https://github.com/quantpulsar/opentelemetry-exporter-langfuse
cd opentelemetry-exporter-langfuse
mvn clean install
```

Then add the dependency:
```xml
<dependency>
    <groupId>com.quantpulsar</groupId>
    <artifactId>opentelemetry-exporter-langfuse</artifactId>
    <version>0.3.2-SNAPSHOT</version>
</dependency>
```

**For Langfuse Cloud:**
```yaml
langfuse:
  public-key: pk-lf-...
  secret-key: sk-lf-...
  host: https://cloud.langfuse.com
```

**For local Langfuse instance (self-hosted):**
```yaml
management:
  langfuse:
    enabled: true
    endpoint: http://localhost:3000/api/public/otel
    public-key: pk-lf-your-public-key
    secret-key: sk-lf-your-secret-key
```

</details>

<details>
<summary><b>Option B: Zipkin</b> (Distributed tracing)</summary>

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

Run Zipkin locally:
```bash
docker run -d -p 9411:9411 openzipkin/zipkin
```

</details>

<details>
<summary><b>Option C: Prometheus + Grafana</b> (Metrics & dashboards)</summary>

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-registry-prometheus</artifactId>
</dependency>
```

```yaml
embabel:
  observability:
    implementation: SPRING_OBSERVATION  # Required for metrics

management:
  endpoints:
    web:
      exposure:
        include: prometheus, health, metrics
  prometheus:
    metrics:
      export:
        enabled: true
```

Metrics available at: `http://localhost:8080/actuator/prometheus`

Run Prometheus + Grafana locally:
```bash
docker run -d -p 9090:9090 prom/prometheus
docker run -d -p 3000:3000 grafana/grafana
```

</details>

<details>
<summary><b>Option D: OTLP</b> (Jaeger, Grafana Tempo, etc.)</summary>

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

</details>

### 4. Done!

Your agents are now fully traced. No code changes required.

---

## Features

### Implemented

| Feature | Description |
|---------|-------------|
| **Agent Lifecycle Tracing** | Full trace of agent creation, execution, completion, and failures |
| **Action Tracing** | Each action execution as a child span with duration and status |
| **Tool Call Tracing** | Every tool invocation with input/output capture |
| **LLM Call Integration** | Spring AI calls automatically appear as child spans |
| **LLM Token Metrics** | Input/output token usage via Spring AI observations |
| **Planning Events** | Track plan formulation and replanning iterations |
| **State Transitions** | Monitor workflow state changes |
| **Lifecycle States** | Visibility into WAITING, PAUSED, STUCK states |
| **Multi-Exporter Support** | Send traces to multiple backends simultaneously |
| **Automatic Metrics** | Duration and count metrics (Spring Observation mode) |

### Coming Soon

| Feature | Target |
|---------|--------|
| RAG Pipeline Tracing | v0.5.x |
| Dynamic Agent Creation Tracing | v0.4.x |
| Pre-built Grafana Dashboards | v1.0.x |
| Cost Analytics Dashboard | v1.0.x |

---

## Supported Backends

| Backend | Type | Module |
|---------|------|--------|
| **Langfuse** | Traces | [`opentelemetry-exporter-langfuse`](https://github.com/quantpulsar/opentelemetry-exporter-langfuse) |
| **Zipkin** | Traces | [`opentelemetry-exporter-zipkin`](https://github.com/open-telemetry/opentelemetry-java) |
| **OTLP** (Jaeger, Tempo) | Traces | [`opentelemetry-exporter-otlp`](https://github.com/open-telemetry/opentelemetry-java) |
| **Prometheus** | Metrics | [`micrometer-registry-prometheus`](https://github.com/micrometer-metrics/micrometer) |
| **Custom** | Traces | Implement `SpanExporter` |

> **Tip:** You can use multiple exporters simultaneously (e.g., Langfuse for traces + Prometheus for metrics).

---

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `embabel.observability.enabled` | `true` | Enable/disable observability |
| `embabel.observability.service-name` | `embabel-agent` | Service name in traces |
| `embabel.observability.implementation` | `SPRING_OBSERVATION` | Tracing backend |
| `embabel.observability.trace-agent-events` | `true` | Trace agent lifecycle |
| `embabel.observability.trace-tool-calls` | `true` | Trace tool invocations (see note below) |
| `embabel.observability.trace-llm-calls` | `true` | Trace LLM calls |
| `embabel.observability.trace-planning` | `true` | Trace planning events |
| `embabel.observability.trace-state-transitions` | `true` | Trace state transitions |
| `embabel.observability.trace-lifecycle-states` | `true` | Trace WAITING/PAUSED/STUCK states |
| `embabel.observability.trace-object-binding` | `false` | Trace object binding (verbose) |
| `embabel.observability.max-attribute-length` | `4000` | Max attribute length |

### Tool Observability Note

> **Important:** Embabel Agent already includes built-in tool observability via `ObservabilityToolCallback`, which provides Micrometer observations for tool calls.
>
> If you prefer to use Embabel's native tool observability instead of this library's implementation, set:
> ```yaml
> embabel:
>   observability:
>     trace-tool-calls: false
> ```
> This avoids duplicate tool call spans and lets Embabel Agent handle tool tracing directly.

### Implementation Modes

| Mode | Traces | Metrics | Recommended |
|------|--------|---------|-------------|
| `SPRING_OBSERVATION` | Yes | Yes | **Yes** |
| `MICROMETER_TRACING` | Yes | No | |
| `OPENTELEMETRY_DIRECT` | Yes | No | |

---

## How It Works

```
┌─────────────────────────────────────────────────────────────┐
│                      EMBABEL AGENT                          │
│   ┌─────────┐  ┌─────────┐  ┌───────┐  ┌──────────┐        │
│   │  Agent  │  │ Actions │  │ Tools │  │ Planning │        │
│   └────┬────┘  └────┬────┘  └───┬───┘  └────┬─────┘        │
└────────┼────────────┼───────────┼───────────┼──────────────┘
         │            │           │           │
         └────────────┴─────┬─────┴───────────┘
                            │
                   ┌────────▼────────┐
                   │  Event Listener │
                   └────────┬────────┘
                            │
         ┌──────────────────┼──────────────────┐
         ▼                  ▼                  ▼
┌─────────────────┐ ┌─────────────────┐ ┌─────────────────┐
│     SPRING      │ │   MICROMETER    │ │  OPENTELEMETRY  │
│   OBSERVATION   │ │    TRACING      │ │     DIRECT      │
│  (Recommended)  │ │                 │ │                 │
│ Traces+Metrics  │ │   Traces Only   │ │   Traces Only   │
└────────┬────────┘ └────────┬────────┘ └────────┬────────┘
         │                   │                   │
         └───────────────────┼───────────────────┘
                             │
                   ┌─────────▼─────────┐
                   │   OpenTelemetry   │
                   │   SpanExporter    │
                   └─────────┬─────────┘
                             │
         ┌───────────┬───────┴───────┬───────────┐
         ▼           ▼               ▼           ▼
    ┌─────────┐ ┌─────────┐    ┌─────────┐ ┌─────────┐
    │Langfuse │ │ Zipkin  │    │  OTLP   │ │Prometheus│
    └─────────┘ └─────────┘    └─────────┘ └─────────┘
```

**Key Points:**
- Automatically captures all Embabel Agent events
- Spring AI LLM calls appear as children of action spans
- Zero code instrumentation required
- Multiple exporters can run simultaneously

---

## Trace Hierarchy Example

```
Agent: CustomerServiceAgent (trace root)
├── planning:formulated [iteration=1, actions=3]
├── Action: AnalyzeRequest
│   └── ChatModel: gpt-4 (Spring AI)
│       └── tool:searchKnowledgeBase
├── Action: GenerateResponse
│   └── ChatModel: gpt-4 (Spring AI)
├── goal:achieved [RequestProcessed]
└── status: completed [duration=2340ms]
```

---

## Roadmap

| Phase | Version | Features |
|-------|---------|----------|
| **Current** | v0.3.x | Agent, Action, Tool, Planning, State tracing, LLM token metrics (via Spring AI) |
| **Short Term** | v0.4.x | Dynamic agent creation tracing, platform events |
| **Medium Term** | v0.5.x | RAG pipeline tracing, RAG metrics |
| **Long Term** | v1.0.x | Grafana dashboards, alerting, cost analytics |

---

## Documentation

For detailed technical documentation, architecture details, and API reference:

**[Technical Guide](docs/TECHNICAL_GUIDE.md)**

---

## Requirements

- Java 21+
- Spring Boot 3.5+
- Embabel Agent 0.3.2+

---

## License

Apache License 2.0 - See [LICENSE](LICENSE) for details.

---

## Contributing

Contributions are welcome! You can help by:
- Reporting bugs or suggesting features
- Submitting pull requests
- Adding or improving tests

> **Note:** This project will be submitted to the [Embabel](https://github.com/embabel) team for potential inclusion as an official add-on.

---

## FAQ

### ClassNotFoundException with OpenTelemetry

**Problem:** You get a `ClassNotFoundException` or `NoClassDefFoundError` related to OpenTelemetry classes.

**Solution:** Add the OpenTelemetry BOM to your project to align all OpenTelemetry dependency versions:

```xml
<dependencyManagement>
  <dependencies>
    <!-- OpenTelemetry BOM - must be first to override other BOMs -->
    <dependency>
      <groupId>io.opentelemetry</groupId>
      <artifactId>opentelemetry-bom</artifactId>
      <version>1.44.1</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
    <!-- ... your other dependencies ... -->
  </dependencies>
</dependencyManagement>
```

After adding the BOM, remove explicit version numbers from your OpenTelemetry dependencies and run a clean build:

```bash
./mvnw clean spring-boot:run
```

---
