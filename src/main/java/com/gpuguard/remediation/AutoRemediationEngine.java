package com.gpuguard.remediation;

import com.gpuguard.simulator.FailureType;
import com.gpuguard.simulator.GPUCluster;
import com.gpuguard.simulator.JobStatus;
import com.gpuguard.simulator.TrainingJob;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

/**
 * Runbook-driven auto-remediation with Circuit Breaker pattern.
 * Equivalent to the Python AutoRemediationEngine class.
 *
 * Uses Java 21 features:
 *   - Switch expressions for runbook dispatch
 *   - Records for RemediationResult
 *   - Sealed interface for runbook results (extensible pattern)
 */
@Slf4j
public class AutoRemediationEngine {

    private final CircuitBreaker circuitBreaker = new CircuitBreaker(3, 300_000L, 600_000L);
    private final List<RemediationResult> remediationLog = new CopyOnWriteArrayList<>();
    private final AtomicInteger totalActions = new AtomicInteger(0);
    private final AtomicInteger successfulActions = new AtomicInteger(0);

    public List<RemediationResult> evaluate(GPUCluster cluster, Object sloReport) {
        List<RemediationResult> actionsThisTick = new ArrayList<>();

        for (TrainingJob job : new ArrayList<>(cluster.getJobs().values())) {
            if (job.getStatus() != JobStatus.FAILED || job.getFailureType() == FailureType.NONE) continue;

            String cbKey = job.getJobId() + ":" + job.getFailureType().getValue();
            if (!circuitBreaker.tryAcquire(cbKey)) continue;

            RemediationResult result = executeRunbook(job);
            actionsThisTick.add(result);
            remediationLog.add(result);
            totalActions.incrementAndGet();

            if (result.success()) {
                successfulActions.incrementAndGet();
                job.setStatus(JobStatus.RECOVERING);
                job.setFailureType(FailureType.NONE);
                circuitBreaker.reset(cbKey);
            }
        }

        return actionsThisTick;
    }

    private RemediationResult executeRunbook(TrainingJob job) {
        long start = System.currentTimeMillis();
        try {
            String message = switch (job.getFailureType()) {
                case NCCL_TIMEOUT   -> handleNcclTimeout(job);
                case OOM            -> handleOom(job);
                case NODE_DOWN      -> handleNodeDown(job);
                case NETWORK_FLAP   -> handleNetworkFlap(job);
                case CHECKSUM_MISMATCH -> handleChecksumMismatch(job);
                case SLOW_NODE      -> handleSlowNode(job);
                default             -> "noop — no runbook for " + job.getFailureType();
            };
            long durationMs = System.currentTimeMillis() - start;
            log.info("[REMEDIATION] success | job={} | failure={} | {}",
                job.getJobId(), job.getFailureType().getValue(), message);
            return new RemediationResult(
                "restart_from_checkpoint", job.getJobId(), true, message, Instant.now(), durationMs);
        } catch (Exception e) {
            long durationMs = System.currentTimeMillis() - start;
            log.error("[REMEDIATION] failed | job={} | {}", job.getJobId(), e.getMessage());
            return new RemediationResult(
                "restart_from_checkpoint", job.getJobId(), false, e.getMessage(), Instant.now(), durationMs);
        }
    }

    // ─── Runbooks ─────────────────────────────────────────────────────────────

    private String handleNcclTimeout(TrainingJob job) {
        job.setStep(job.getCheckpointStep());
        return "Reset NCCL communicators, restarted from checkpoint step " + job.getCheckpointStep();
    }

    private String handleOom(TrainingJob job) {
        // In a real system: update training config to halve micro-batch size
        job.setStep(job.getCheckpointStep());
        return "Reduced micro-batch size, restarted from step " + job.getCheckpointStep();
    }

    private String handleNodeDown(TrainingJob job) {
        var failedNodes = job.getNodes().stream().filter(n -> !n.isHealthy()).toList();
        var healthyNodes = job.getNodes().stream().filter(n -> n.isHealthy()).toList();
        if (healthyNodes.isEmpty()) throw new IllegalStateException("No healthy nodes available for rescheduling");

        failedNodes.forEach(n -> log.info("Cordoning node {}", n.getNodeId()));
        job.setNodes(new ArrayList<>(healthyNodes));
        job.setStep(job.getCheckpointStep());
        return String.format("Cordoned %d nodes, restarted on %d nodes from step %d",
            failedNodes.size(), healthyNodes.size(), job.getCheckpointStep());
    }

    private String handleNetworkFlap(TrainingJob job) {
        int maxFlaps = job.getNodes().stream().mapToInt(n -> n.getFlapCount()).max().orElse(0);
        long backoffMs = Math.min(300_000L, 5_000L * (1L << maxFlaps));
        job.getNodes().forEach(n -> n.setFlapCount(0));
        return String.format("Network flap resolved, backoff=%ds, reset flap counters", backoffMs / 1000);
    }

    private String handleChecksumMismatch(TrainingJob job) {
        int rollbackStep = Math.max(0, job.getCheckpointStep() - 100);
        job.setStep(rollbackStep);
        return "Gradient mismatch: rolled back to step " + rollbackStep;
    }

    private String handleSlowNode(TrainingJob job) {
        var stragglers = job.getNodes().stream()
            .filter(n -> n.getFailureType() == FailureType.SLOW_NODE)
            .toList();
        stragglers.forEach(n -> {
            n.setFailureType(FailureType.NONE);
            n.setHealthy(true);
        });
        job.setStep(job.getCheckpointStep());
        return String.format("Identified %d straggler(s), restarting with re-profiling from step %d",
            stragglers.size(), job.getCheckpointStep());
    }

    // ─── Stats ────────────────────────────────────────────────────────────────

    public RemediationStats getStats() {
        List<RemediationResult> recent = remediationLog.stream()
            .skip(Math.max(0, remediationLog.size() - 10))
            .sorted(Comparator.comparing(RemediationResult::timestamp).reversed())
            .toList();

        double successRate = totalActions.get() == 0 ? 100.0
            : (double) successfulActions.get() / totalActions.get() * 100.0;

        return new RemediationStats(totalActions.get(), successfulActions.get(),
            Math.round(successRate * 10.0) / 10.0, recent);
    }

    // ─── Records ──────────────────────────────────────────────────────────────

    public record RemediationResult(
        String action,
        String jobId,
        boolean success,
        String message,
        Instant timestamp,
        long durationMs
    ) {}

    public record RemediationStats(
        int totalActions,
        int successfulActions,
        double successRatePct,
        List<RemediationResult> recentActions
    ) {}
}
