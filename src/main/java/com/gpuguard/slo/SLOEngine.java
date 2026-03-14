package com.gpuguard.slo;

import com.gpuguard.simulator.GPUCluster;
import com.gpuguard.simulator.GPUNode;
import com.gpuguard.simulator.JobStatus;
import com.gpuguard.simulator.TrainingJob;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Evaluates 4 SLOs every simulation tick and manages incident lifecycle.
 * Equivalent to the Python SLOEngine class.
 */
@Slf4j
public class SLOEngine {

    private static final double THROUGHPUT_BASELINE_TOKENS_PER_SEC = 500_000.0;
    private static final double THROUGHPUT_THRESHOLD_PCT = 0.80;
    private static final double GPU_UTIL_THRESHOLD_PCT = 85.0;
    private static final double MTTR_TARGET_SECONDS = 300.0; // 5 minutes

    // Four production SLOs
    private final SLOWindow jobAvailability    = new SLOWindow("job_availability",    99.5);
    private final SLOWindow trainingThroughput = new SLOWindow("training_throughput", 95.0);
    private final SLOWindow gpuUtilization     = new SLOWindow("gpu_utilization",     90.0);
    private final SLOWindow mttr               = new SLOWindow("mttr",                90.0);

    @Getter private final Map<String, FailureEvent> activeFailures = new ConcurrentHashMap<>();
    @Getter private final List<FailureEvent> resolvedFailures = new CopyOnWriteArrayList<>();
    @Getter private final List<IncidentEntry> incidentLog = new CopyOnWriteArrayList<>();

    public SloEngineReport evaluate(GPUCluster cluster) {
        List<TrainingJob> jobs = new ArrayList<>(cluster.getJobs().values());
        List<TrainingJob> running = jobs.stream().filter(j -> j.getStatus() == JobStatus.RUNNING).toList();
        List<TrainingJob> failed  = jobs.stream().filter(j -> j.getStatus() == JobStatus.FAILED).toList();

        // ── SLO 1: Job Availability ───────────────────────────────────────────
        if (!jobs.isEmpty()) {
            boolean ok = failed.isEmpty() || (double) failed.size() / jobs.size() < 0.05;
            jobAvailability.record(ok);
        }

        // ── SLO 2: Training Throughput ────────────────────────────────────────
        if (!running.isEmpty()) {
            double totalThroughput = running.stream().mapToDouble(TrainingJob::getThroughputTokensPerSec).sum();
            double expected = running.size() * THROUGHPUT_BASELINE_TOKENS_PER_SEC;
            trainingThroughput.record(totalThroughput >= expected * THROUGHPUT_THRESHOLD_PCT);
        }

        // ── SLO 3: GPU Utilization ────────────────────────────────────────────
        List<GPUNode> healthyNodes = running.stream()
            .flatMap(j -> j.getNodes().stream())
            .filter(GPUNode::isHealthy)
            .toList();
        if (!healthyNodes.isEmpty()) {
            double avg = healthyNodes.stream().mapToDouble(GPUNode::getGpuUtilization).average().orElse(0);
            gpuUtilization.record(avg >= GPU_UTIL_THRESHOLD_PCT);
        }

        // ── SLO 4: MTTR ───────────────────────────────────────────────────────
        Instant now = Instant.now();
        Set<String> activeJobIds = cluster.getJobs().keySet();

        // Open new incidents for newly failed jobs
        for (TrainingJob job : failed) {
            activeFailures.computeIfAbsent(job.getJobId(), id -> {
                logIncident("FAILURE_DETECTED", job.getJobId(), job.getFailureType().getValue(), null);
                return new FailureEvent(job.getJobId(), job.getFailureType().getValue(), now);
            });
        }

        // Resolve incidents for jobs no longer in cluster
        for (String jobId : new ArrayList<>(activeFailures.keySet())) {
            if (!activeJobIds.contains(jobId)) {
                FailureEvent event = activeFailures.remove(jobId);
                if (event != null) {
                    event = event.withResolvedAt(now);
                    resolvedFailures.add(event);
                    boolean mttrOk = event.mttrSeconds() <= MTTR_TARGET_SECONDS;
                    mttr.record(mttrOk);
                    logIncident("FAILURE_RESOLVED", jobId, event.failureType(),
                        Map.of("mttr_seconds", String.valueOf(Math.round(event.mttrSeconds()))));
                }
            }
        }

        return buildReport();
    }

    private void logIncident(String eventType, String jobId, String failureType, Map<String, String> extra) {
        incidentLog.add(new IncidentEntry(Instant.now(), eventType, jobId, failureType, extra));
        log.info("[INCIDENT] {} | job={} | failure={}", eventType, jobId, failureType);
    }

    private SloEngineReport buildReport() {
        var slos = Map.of(
            "job_availability",    jobAvailability.toReport(),
            "training_throughput", trainingThroughput.toReport(),
            "gpu_utilization",     gpuUtilization.toReport(),
            "mttr",                mttr.toReport()
        );

        String overall = slos.values().stream()
            .map(r -> r.status())
            .reduce("OK", (a, b) -> {
                if ("CRITICAL".equals(a) || "CRITICAL".equals(b)) return "CRITICAL";
                if ("WARNING".equals(a)  || "WARNING".equals(b))  return "WARNING";
                if ("AT_RISK".equals(a)  || "AT_RISK".equals(b))  return "AT_RISK";
                return "OK";
            });

        OptionalDouble recentMttr = resolvedFailures.stream()
            .skip(Math.max(0, resolvedFailures.size() - 5))
            .mapToDouble(FailureEvent::mttrSeconds)
            .average();

        List<IncidentEntry> recentIncidents = incidentLog.stream()
            .skip(Math.max(0, incidentLog.size() - 10))
            .sorted(Comparator.comparing(IncidentEntry::timestamp).reversed())
            .toList();

        return new SloEngineReport(
            overall, slos, activeFailures.size(),
            recentMttr.isPresent() ? Math.round(recentMttr.getAsDouble()) : null,
            recentIncidents
        );
    }

    // ─── Public accessors for Micrometer metric export ────────────────────────
    public Map<String, SLOWindow> getAllSLOs() {
        return Map.of(
            "job_availability",    jobAvailability,
            "training_throughput", trainingThroughput,
            "gpu_utilization",     gpuUtilization,
            "mttr",                mttr
        );
    }

    // ─── Records (Java 21) ────────────────────────────────────────────────────

    public record FailureEvent(String jobId, String failureType, Instant occurredAt, Instant resolvedAt) {
        public FailureEvent(String jobId, String failureType, Instant occurredAt) {
            this(jobId, failureType, occurredAt, null);
        }
        public FailureEvent withResolvedAt(Instant t) {
            return new FailureEvent(jobId, failureType, occurredAt, t);
        }
        public double mttrSeconds() {
            if (resolvedAt == null) return 0.0;
            return (double) (resolvedAt.toEpochMilli() - occurredAt.toEpochMilli()) / 1000.0;
        }
    }

    public record IncidentEntry(
        Instant timestamp,
        String eventType,
        String jobId,
        String failureType,
        Map<String, String> extra
    ) {}

    public record SloEngineReport(
        String overallStatus,
        Map<String, SLOWindow.SloReport> slos,
        int activeFailures,
        Long recentMttrAvgSeconds,
        List<IncidentEntry> recentIncidents
    ) {}
}
