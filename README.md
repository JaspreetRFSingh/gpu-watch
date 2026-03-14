# GPUGuard — AI Training Infrastructure Reliability Platform

A production-grade observability and reliability platform for GPU training clusters, built with **Spring Boot 3.2** and **Java 21**. GPUGuard simulates a live multi-job GPU cluster, continuously evaluates SLOs, auto-remediates failures, and surfaces statistical anomalies — all exposed via a REST API and a Prometheus/Grafana observability stack.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                        Spring Boot Application                              │
│                                                                             │
│  ┌──────────────────────────────────────────────────────────────────────┐  │
│  │              SimulationService  (@Scheduled — every 5s)              │  │
│  │                                                                      │  │
│  │   ┌─────────────┐    tick()    ┌───────────────────────────────┐    │  │
│  │   │  GPUCluster │ ──────────►  │         Tick Pipeline         │    │  │
│  │   │             │              │                               │    │  │
│  │   │ • Spawns /  │              │  1. cluster.tick()            │    │  │
│  │   │   completes │              │     └─ advance job steps      │    │  │
│  │   │   jobs      │              │     └─ inject random failures  │    │  │
│  │   │ • Injects   │              │     └─ checkpoint saves       │    │  │
│  │   │   failures  │              │                               │    │  │
│  │   │ • Manages   │              │  2. sloEngine.evaluate()      │    │  │
│  │   │   restarts  │              │     └─ job availability SLO   │    │  │
│  │   │             │              │     └─ throughput SLO         │    │  │
│  │   │ ConcurrentH │              │     └─ GPU utilization SLO    │    │  │
│  │   │ ashMap,     │              │     └─ MTTR SLO               │    │  │
│  │   │ AtomicInt   │              │     └─ incident lifecycle      │    │  │
│  │   └─────────────┘              │                               │    │  │
│  │          ▲                     │  3. remediationEngine.eval()  │    │  │
│  │          │                     │     └─ circuit breaker check  │    │  │
│  │   POST /api/v1/jobs/spawn      │     └─ runbook dispatch       │    │  │
│  │                                │        (switch expression)    │    │  │
│  │                                │                               │    │  │
│  │                                │  4. anomalyEngine.evaluate()  │    │  │
│  │                                │     └─ Z-score (spikes)       │    │  │
│  │                                │     └─ CUSUM (drift)          │    │  │
│  │                                │     └─ IQR (outliers)         │    │  │
│  │                                └───────────────────────────────┘    │  │
│  └──────────────────────────────────────────────────────────────────────┘  │
│                                                                             │
│  ┌──────────────────────┐     ┌──────────────────────────────────────────┐ │
│  │   MetricsExporter    │     │         GpuGuardController               │ │
│  │                      │     │         (@RestController)                │ │
│  │  Micrometer gauges / │     │                                          │ │
│  │  counters registered │     │  GET  /api/v1/health                     │ │
│  │  at startup          │     │  GET  /api/v1/cluster                    │ │
│  │                      │     │  GET  /api/v1/jobs                       │ │
│  │  Reads live from:    │     │  GET  /api/v1/slo                        │ │
│  │  • GPUCluster        │     │  GET  /api/v1/incidents                  │ │
│  │  • SLOEngine         │     │  GET  /api/v1/remediation                │ │
│  │  • Remediation       │     │  GET  /api/v1/anomalies                  │ │
│  │  • Anomaly           │     │  POST /api/v1/jobs/spawn                 │ │
│  └──────────┬───────────┘     └──────────────────────────────────────────┘ │
│             │                                                               │
│             ▼                                                               │
│   /actuator/prometheus                                                      │
└─────────────┬───────────────────────────────────────────────────────────────┘
              │
              ▼
    ┌─────────────────┐          ┌─────────────────┐
    │   Prometheus    │ ──────►  │     Grafana      │
    │  (scrapes :8080 │  query   │  (dashboards)   │
    │   every 15s)    │          │  :3000           │
    └─────────────────┘          └─────────────────┘
```

### Simulation tick detail

```
Every 5 real seconds = one simulation tick (~30s of simulated time)

GPUCluster.tick()
├── Maybe spawn a new job (15% chance if cluster < capacity)
└── For each active job:
    ├── RECOVERING  →  transition to RUNNING
    ├── RUNNING
    │   ├── Advance step counter + elapsed time
    │   ├── Update loss + throughput metrics
    │   ├── Save checkpoint every 50 steps
    │   ├── Tick all GPUNodes (simulate GPU utilisation, temp, NVLink)
    │   ├── Inject random failure (NETWORK_FLAP / NCCL_TIMEOUT /
    │   │                          OOM / NODE_DOWN / SLOW_NODE)
    │   └── Mark COMPLETED if step >= totalSteps
    └── FAILED
        └── attemptRestart() — rollback to checkpoint, heal nodes
                             — max 3 restarts before eviction

SLOEngine.evaluate()
├── SLO 1 — Job Availability   (target 99.5%) : < 5% of jobs failed
├── SLO 2 — Training Throughput (target 95%)  : ≥ 80% of baseline tokens/s
├── SLO 3 — GPU Utilisation    (target 90%)   : avg healthy node util ≥ 85%
└── SLO 4 — MTTR               (target 90%)   : recovery ≤ 300s
    └── Each SLO maintains a rolling error budget via SLOWindow

AutoRemediationEngine.evaluate()
├── CircuitBreaker guards each (jobId, failureType) pair
│   └── Opens after 3 failures, resets after 600s
└── Runbook dispatch (Java 21 switch expression):
    ├── NCCL_TIMEOUT    → reset communicators, restart from checkpoint
    ├── OOM             → halve micro-batch size, restart from checkpoint
    ├── NODE_DOWN       → cordon failed nodes, restart on healthy nodes
    ├── NETWORK_FLAP    → exponential backoff, reset flap counters
    ├── SLOW_NODE       → identify stragglers, restart with re-profiling
    └── CHECKSUM_MISMATCH → roll back 100 steps, restart

AnomalyDetectionEngine.evaluate()
└── Per metric per entity (training_loss, throughput, gpu_util,
    gpu_memory, gpu_temp):
    ├── Z-score  — Welford online mean/variance, threshold ±3σ / ±4.5σ
    ├── CUSUM    — Cumulative sum for sustained drift detection
    └── IQR      — Interquartile range outlier fence (2.5× IQR)
```

---

## Tech stack

| Layer | Technology |
|---|---|
| Framework | Spring Boot 3.2 + Spring MVC |
| Scheduling | `@Scheduled` (Spring task scheduler) |
| Metrics | Micrometer + `micrometer-registry-prometheus` |
| Serialization | Jackson (`SNAKE_CASE` naming strategy) |
| Boilerplate reduction | Lombok (`@Data`, `@Slf4j`, `@RequiredArgsConstructor`) |
| Models | Java 21 Records + Lombok `@Data` |
| Thread safety | `ConcurrentHashMap`, `CopyOnWriteArrayList`, `AtomicInteger`, `AtomicReference` |
| HTTP server | Embedded Tomcat (via Spring Boot) |
| Testing | JUnit 5 + MockMvc + Awaitility |
| Coverage | JaCoCo |
| Containerisation | Docker + Docker Compose |
| Observability | Prometheus + Grafana |

---

## Project structure

```
src/main/java/com/gpuguard/
├── GpuGuardApplication.java          # Entry point — @SpringBootApplication + @EnableScheduling
├── config/
│   └── GpuGuardConfig.java           # @Bean wiring (GPUCluster, SLOEngine, remediation, anomaly)
├── simulator/
│   ├── GPUCluster.java               # Cluster simulation engine — thread-safe
│   ├── TrainingJob.java              # Training job model (Lombok @Data, per-node list)
│   ├── GPUNode.java                  # GPU node model — simulateTick(), flap counters
│   ├── JobStatus.java                # Enum: QUEUED, RUNNING, FAILED, RECOVERING, COMPLETED
│   └── FailureType.java              # Enum: OOM, NCCL_TIMEOUT, NODE_DOWN, NETWORK_FLAP, SLOW_NODE
├── slo/
│   ├── SLOWindow.java                # Rolling-window SLI / error budget / burn rate calculator
│   └── SLOEngine.java                # Evaluates 4 SLOs, manages incident lifecycle
├── remediation/
│   ├── CircuitBreaker.java           # Prevents retry storms — per (jobId, failureType) key
│   └── AutoRemediationEngine.java    # Runbook dispatch via switch expression
├── anomaly/
│   ├── AnomalyDetectionEngine.java   # Per-entity tracker management, evicts stale trackers
│   ├── MetricTracker.java            # Welford + CUSUM + IQR per metric time series
│   └── AnomalyEvent.java             # Immutable record (metric, entity, type, severity)
├── metrics/
│   └── MetricsExporter.java          # Micrometer gauge/counter registration at startup
├── service/
│   └── SimulationService.java        # @Scheduled tick loop — wires all engines together
└── controller/
    └── GpuGuardController.java       # REST endpoints + JobDto / NodeDto records

src/test/java/com/gpuguard/
├── slo/SLOWindowTest.java            # Unit tests — rolling window, error budget, burn rate
├── simulator/GPUClusterTest.java     # Unit tests — @RepeatedTest for probabilistic behaviour
├── simulator/CircuitBreakerTest.java # Unit tests — open/closed/half-open state transitions
└── controller/GpuGuardControllerTest.java  # Integration tests — MockMvc + Awaitility
```

---

## Prerequisites

- **Java 21+** — `java --version`
- **Maven 3.9+** — or use the bundled `./mvnw` wrapper (no Maven install required)
- **Docker + Docker Compose** — required only for the full observability stack (Option C)

---

## Running locally

### Option A — Maven wrapper (fastest)

```bash
./mvnw spring-boot:run
```

Server starts at `http://localhost:8080`. The simulation begins immediately with 3 seeded jobs.

### Option B — Build fat jar and run

```bash
./mvnw clean package -DskipTests
java -jar target/gpuguard-1.0.0.jar
```

### Option C — Docker Compose (Prometheus + Grafana included)

```bash
docker compose up -d
```

| Service | URL | Credentials |
|---|---|---|
| GPUGuard API | `http://localhost:8080` | — |
| Prometheus | `http://localhost:9090` | — |
| Grafana | `http://localhost:3000` | admin / gpuguard |

Prometheus scrapes `http://gpuguard:8080/actuator/prometheus` every 15 seconds.
Metrics are retained for 7 days (`--storage.tsdb.retention.time=7d`).

---

## API endpoints

```
GET  /api/v1/health          Liveness check — tick count and uptime
GET  /api/v1/cluster         Cluster-level stats (jobs, failures, throughput, GPU util)
GET  /api/v1/jobs            All active jobs with per-node metrics
GET  /api/v1/slo             SLO error budgets, burn rates, and overall status
GET  /api/v1/incidents       Incident log and average MTTR
GET  /api/v1/remediation     Auto-remediation action log and success rate
GET  /api/v1/anomalies       Anomaly detection stats and recent events

POST /api/v1/jobs/spawn      Manually spawn a training job
     ?modelName=llama-3-70b  (default: llama-3-70b)
     &numNodes=4              (default: 4)

GET  /actuator/health        Spring Boot health indicator
GET  /actuator/prometheus    Prometheus scrape endpoint
GET  /actuator/metrics       Micrometer metrics browser
```

### Example responses

```bash
# Cluster overview
curl http://localhost:8080/api/v1/cluster

# SLO status with error budgets
curl http://localhost:8080/api/v1/slo

# Spawn a custom job
curl -X POST "http://localhost:8080/api/v1/jobs/spawn?modelName=falcon-180b&numNodes=8"
```

---

## Running tests

```bash
# Unit tests only — fast, no Spring context
./mvnw test -Dtest="SLOWindowTest,GPUClusterTest,CircuitBreakerTest"

# All tests including integration (spins up full Spring context + MockMvc)
./mvnw test

# With JaCoCo coverage report
./mvnw test jacoco:report
open target/site/jacoco/index.html
```

| Test class | Type | What it covers |
|---|---|---|
| `SLOWindowTest` | Unit | Rolling window accuracy, error budget depletion, burn rate calculation |
| `GPUClusterTest` | Unit | Job lifecycle, failure injection probabilities (`@RepeatedTest`) |
| `CircuitBreakerTest` | Unit | Open/closed/half-open state transitions and timeout reset |
| `GpuGuardControllerTest` | Integration | All REST endpoints via MockMvc, async state via Awaitility |

---

## Key design decisions

**Thread safety without a GIL**
All shared state uses Java concurrency primitives: `ConcurrentHashMap` for job maps, `CopyOnWriteArrayList` for incident/anomaly logs, `AtomicInteger` for counters, and `AtomicReference` for the latest SLO report snapshot. No `synchronized` blocks are needed.

**Plain-Java simulation core**
`GPUCluster`, `SLOEngine`, `AutoRemediationEngine`, and `AnomalyDetectionEngine` have no Spring imports. They are constructed as `@Bean`s in `GpuGuardConfig` and injected where needed. This makes them fully unit-testable without a Spring context.

**Vendor-neutral metrics**
Micrometer provides a metrics facade over Prometheus. Switching the export target to Datadog, CloudWatch, or InfluxDB requires changing one `pom.xml` dependency — no application code changes.

**Runbook dispatch via switch expressions**
`AutoRemediationEngine` uses a Java 21 switch expression to dispatch failure types to runbook handlers. Adding a new `FailureType` causes a compile error if no runbook is provided, making omissions explicit at build time.

**JSON field naming**
`spring.jackson.property-naming-strategy=SNAKE_CASE` in `application.yml` automatically serialises Java camelCase fields (`jobId`, `modelName`) as `job_id`, `model_name` in all API responses. No `@JsonProperty` annotations are needed on model classes.

**Statistical anomaly detection without an ML framework**
`MetricTracker` implements three algorithms in pure Java with O(1) memory per metric:
- **Welford's online algorithm** for rolling mean and variance — avoids storing the full window
- **CUSUM** for detecting sustained directional drift
- **IQR fencing** as a distribution-robust fallback

---

## Java 21 features used

| Feature | Where |
|---|---|
| Records | `ClusterStats`, `SloReport`, `IncidentEntry`, `AnomalyEvent`, `RemediationResult`, `JobDto`, `NodeDto` |
| Switch expressions | Runbook dispatch in `AutoRemediationEngine`, failure handling in `GPUCluster` |
| `var` local type inference | Controller and service layer throughout |
| Text blocks | Test assertions in `GpuGuardControllerTest` |
| Pattern matching | Switch arms in `GPUCluster.handleFailure()` |
| Sequenced collections | `.toList()` on streams (returns unmodifiable list) |