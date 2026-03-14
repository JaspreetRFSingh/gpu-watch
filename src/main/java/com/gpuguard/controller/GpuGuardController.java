package com.gpuguard.controller;

import com.gpuguard.anomaly.AnomalyDetectionEngine;
import com.gpuguard.remediation.AutoRemediationEngine;
import com.gpuguard.service.SimulationService;
import com.gpuguard.simulator.GPUCluster;
import com.gpuguard.simulator.GPUNode;
import com.gpuguard.simulator.TrainingJob;
import com.gpuguard.slo.SLOEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API controllers — Spring MVC equivalent of Python FastAPI routes.
 *
 * Endpoints:
 *   GET  /api/v1/cluster           → cluster-level stats
 *   GET  /api/v1/jobs              → all active jobs with per-node metrics
 *   GET  /api/v1/slo               → SLO error budgets + burn rates
 *   GET  /api/v1/incidents         → incident log + MTTR
 *   GET  /api/v1/remediation       → auto-remediation stats
 *   GET  /api/v1/anomalies         → anomaly detection stats
 *   POST /api/v1/jobs/spawn        → manually spawn a training job
 *   GET  /health                   → liveness check (also at /actuator/health)
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class GpuGuardController {

    private final GPUCluster cluster;
    private final SLOEngine sloEngine;
    private final AutoRemediationEngine remediationEngine;
    private final AnomalyDetectionEngine anomalyEngine;
    private final SimulationService simulationService;

    // ─── Health ───────────────────────────────────────────────────────────────
    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
            "status", "ok",
            "tick", cluster.getTickCount().get(),
            "uptime_seconds", cluster.getTickCount().get() * 5L
        );
    }

    // ─── Cluster ──────────────────────────────────────────────────────────────
    @GetMapping("/cluster")
    public GPUCluster.ClusterStats getCluster() {
        return cluster.getStats();
    }

    // ─── Jobs ─────────────────────────────────────────────────────────────────
    @GetMapping("/jobs")
    public List<JobDto> listJobs() {
        return cluster.getJobs().values().stream()
            .map(this::toJobDto)
            .toList();
    }

    @PostMapping("/jobs/spawn")
    public ResponseEntity<?> spawnJob(
        @RequestParam(defaultValue = "llama-3-70b") String modelName,
        @RequestParam(defaultValue = "4") int numNodes
    ) {
        if (cluster.getJobs().size() >= 6) {
            return ResponseEntity.status(429).body(Map.of("error", "Cluster at capacity"));
        }
        TrainingJob job = cluster.spawnJob(modelName, numNodes);
        return ResponseEntity.ok(Map.of(
            "job_id", job.getJobId(),
            "model_name", modelName,
            "num_nodes", numNodes
        ));
    }

    // ─── SLO ──────────────────────────────────────────────────────────────────
    @GetMapping("/slo")
    public Object getSloReport() {
        var report = simulationService.getLatestSloReport().get();
        return report != null ? report : Map.of("status", "warming_up");
    }

    // ─── Incidents ────────────────────────────────────────────────────────────
    @GetMapping("/incidents")
    public Map<String, Object> getIncidents() {
        var recentIncidents = sloEngine.getIncidentLog().stream()
            .skip(Math.max(0, sloEngine.getIncidentLog().size() - 20))
            .sorted((a, b) -> b.timestamp().compareTo(a.timestamp()))
            .toList();

        double avgMttr = sloEngine.getResolvedFailures().stream()
            .skip(Math.max(0, sloEngine.getResolvedFailures().size() - 10))
            .mapToDouble(SLOEngine.FailureEvent::mttrSeconds)
            .average()
            .orElse(0.0);

        return Map.of(
            "active_failures", sloEngine.getActiveFailures().size(),
            "resolved_failures", sloEngine.getResolvedFailures().size(),
            "recent_incidents", recentIncidents,
            "recent_mttr_avg_seconds", Math.round(avgMttr)
        );
    }

    // ─── Remediation ──────────────────────────────────────────────────────────
    @GetMapping("/remediation")
    public AutoRemediationEngine.RemediationStats getRemediation() {
        return remediationEngine.getStats();
    }

    // ─── Anomalies ────────────────────────────────────────────────────────────
    @GetMapping("/anomalies")
    public AnomalyDetectionEngine.AnomalyStats getAnomalies() {
        return anomalyEngine.getStats();
    }

    // ─── DTO mapping ──────────────────────────────────────────────────────────
    private JobDto toJobDto(TrainingJob job) {
        List<NodeDto> nodes = job.getNodes().stream()
            .map(n -> new NodeDto(
                n.getNodeId(), n.isHealthy(),
                round(n.getGpuUtilization()), round(n.getGpuMemoryUsedGb()),
                round(n.getGpuMemoryUtilization()), round(n.getGpuTempCelsius()),
                round(n.getNvlinkBandwidthGbps()), n.getFlapCount()
            )).toList();

        return new JobDto(
            job.getJobId(), job.getModelName(), job.getStatus().name().toLowerCase(),
            job.getStep(), job.getTotalSteps(), round(job.getProgressPct()),
            round(job.getLoss()), (long) job.getThroughputTokensPerSec(),
            job.getRestartCount(), job.getNumNodes(),
            job.getFailureType().getValue(), job.getElapsedSeconds(), nodes
        );
    }

    private static double round(double val) {
        return Math.round(val * 100.0) / 100.0;
    }

    // ─── Records (Java 21) ────────────────────────────────────────────────────
    public record JobDto(
        String jobId, String modelName, String status,
        int step, int totalSteps, double progressPct,
        double loss, long throughputTokensPerSec,
        int restartCount, int numNodes, String failureType,
        long elapsedSeconds, List<NodeDto> nodes
    ) {}

    public record NodeDto(
        String nodeId, boolean isHealthy,
        double gpuUtilization, double gpuMemoryUsedGb,
        double gpuMemoryUtilizationPct, double gpuTempCelsius,
        double nvlinkBandwidthGbps, int flapCount
    ) {}
}
