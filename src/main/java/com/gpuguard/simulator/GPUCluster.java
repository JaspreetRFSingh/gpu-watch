package com.gpuguard.simulator;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Simulates a GPU cluster running multiple concurrent training jobs.
 * Thread-safe: ConcurrentHashMap for jobs, AtomicInteger for counters.
 * Equivalent to the Python GPUCluster class.
 */
@Slf4j
public class GPUCluster {

    private static final String[] MODELS = {
        "llama-3-70b", "mistral-8x7b", "falcon-180b", "gemma-27b", "phi-4"
    };
    private static final int[] NODE_COUNTS = {2, 4, 8};

    // Failure injection probabilities per tick
    private static final Map<FailureType, Double> FAILURE_RATES = Map.of(
        FailureType.NETWORK_FLAP,    0.002,
        FailureType.NCCL_TIMEOUT,    0.001,
        FailureType.OOM,             0.0008,
        FailureType.NODE_DOWN,       0.0005,
        FailureType.SLOW_NODE,       0.003
    );

    private final int maxConcurrentJobs;
    private final Random rng = new Random();

    @Getter private final Map<String, TrainingJob> jobs = new ConcurrentHashMap<>();
    @Getter private final List<TrainingJob> completedJobs = Collections.synchronizedList(new ArrayList<>());
    @Getter private final AtomicInteger totalFailures = new AtomicInteger(0);
    @Getter private final AtomicInteger totalRestarts = new AtomicInteger(0);
    @Getter private final AtomicInteger tickCount = new AtomicInteger(0);

    public GPUCluster(int maxConcurrentJobs) {
        this.maxConcurrentJobs = maxConcurrentJobs;
    }

    public TrainingJob spawnJob(String modelName, int numNodes) {
        int totalSteps = 500 + rng.nextInt(1500);
        TrainingJob job = new TrainingJob(modelName, numNodes, totalSteps);
        job.setStatus(JobStatus.RUNNING);
        jobs.put(job.getJobId(), job);
        log.info("Spawned job {} ({}, {} nodes, {} steps)", job.getJobId(), modelName, numNodes, totalSteps);
        return job;
    }

    public TrainingJob spawnJob() {
        String model = MODELS[rng.nextInt(MODELS.length)];
        int nodes = NODE_COUNTS[rng.nextInt(NODE_COUNTS.length)];
        return spawnJob(model, nodes);
    }

    /**
     * Advances simulation by one time step (~30 seconds of simulated time).
     * Called by the @Scheduled SimulationService every 5 real seconds.
     */
    public void tick() {
        tickCount.incrementAndGet();

        // Spawn new jobs if cluster has capacity
        if (jobs.size() < maxConcurrentJobs && rng.nextDouble() < 0.15) {
            spawnJob();
        }

        List<String> toRemove = new ArrayList<>();

        for (TrainingJob job : jobs.values()) {
            switch (job.getStatus()) {
                case RECOVERING -> {
                    job.setStatus(JobStatus.RUNNING);
                }
                case RUNNING -> tickRunningJob(job, toRemove);
                case FAILED -> tickFailedJob(job, toRemove);
                default -> { /* QUEUED, COMPLETED, PREEMPTED — no-op */ }
            }
        }

        toRemove.forEach(id -> {
            TrainingJob done = jobs.remove(id);
            if (done != null) completedJobs.add(done);
        });
    }

    private void tickRunningJob(TrainingJob job, List<String> toRemove) {
        // Advance training step
        job.setStep(job.getStep() + 1 + rng.nextInt(4));
        job.setElapsedSeconds(job.getElapsedSeconds() + 30);
        job.setLoss(job.simulateLoss());
        job.setThroughputTokensPerSec(job.simulateThroughput());

        // Save checkpoint every 50 steps
        if (job.getStep() % 50 < 5) {
            job.setCheckpointStep(job.getStep());
        }

        // Tick all nodes
        job.getNodes().forEach(n -> n.simulateTick(true));

        // Inject potential failure
        FailureType failure = injectFailure();
        if (failure != null) {
            handleFailure(job, failure);
        }

        // Check completion
        if (job.getStep() >= job.getTotalSteps()) {
            job.setStatus(JobStatus.COMPLETED);
            log.info("Job {} completed at step {}", job.getJobId(), job.getStep());
            toRemove.add(job.getJobId());
        }
    }

    private void tickFailedJob(TrainingJob job, List<String> toRemove) {
        if (!attemptRestart(job)) {
            log.error("Job {} exceeded max restarts. Terminating.", job.getJobId());
            toRemove.add(job.getJobId());
        }
    }

    private FailureType injectFailure() {
        for (Map.Entry<FailureType, Double> entry : FAILURE_RATES.entrySet()) {
            if (rng.nextDouble() < entry.getValue()) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void handleFailure(TrainingJob job, FailureType failure) {
        totalFailures.incrementAndGet();
        log.warn("Job {} encountered {} at step {}", job.getJobId(), failure.getValue(), job.getStep());
        job.setFailureType(failure);

        switch (failure) {
            case NODE_DOWN -> {
                // Kill a random node
                List<GPUNode> nodes = job.getNodes();
                GPUNode victim = nodes.get(rng.nextInt(nodes.size()));
                victim.setHealthy(false);
                job.setStatus(JobStatus.FAILED);
            }
            case NCCL_TIMEOUT, OOM -> job.setStatus(JobStatus.FAILED);
            case NETWORK_FLAP -> {
                job.getNodes().forEach(n -> n.setFlapCount(n.getFlapCount() + 1));
                if (rng.nextDouble() < 0.3) job.setStatus(JobStatus.FAILED);
            }
            case SLOW_NODE -> {
                GPUNode slowNode = job.getNodes().get(rng.nextInt(job.getNodes().size()));
                slowNode.setFailureType(FailureType.SLOW_NODE);
            }
            default -> { }
        }
    }

    private boolean attemptRestart(TrainingJob job) {
        if (job.getRestartCount() >= job.getMaxRestarts()) return false;

        job.setRestartCount(job.getRestartCount() + 1);
        totalRestarts.incrementAndGet();
        job.setStep(job.getCheckpointStep());
        job.setFailureType(FailureType.NONE);

        // Heal all nodes
        job.getNodes().forEach(n -> {
            n.setHealthy(true);
            n.setFailureType(FailureType.NONE);
            n.setFlapCount(0);
        });

        job.setStatus(JobStatus.RECOVERING);
        log.info("Job {} restarting (attempt {}) from checkpoint step {}",
            job.getJobId(), job.getRestartCount(), job.getCheckpointStep());
        return true;
    }

    public ClusterStats getStats() {
        List<TrainingJob> running = jobs.values().stream()
            .filter(j -> j.getStatus() == JobStatus.RUNNING)
            .toList();
        List<TrainingJob> failed = jobs.values().stream()
            .filter(j -> j.getStatus() == JobStatus.FAILED)
            .toList();

        double avgUtil = running.stream()
            .flatMap(j -> j.getNodes().stream())
            .mapToDouble(GPUNode::getGpuUtilization)
            .average()
            .orElse(0.0);

        double totalThroughput = running.stream()
            .mapToDouble(TrainingJob::getThroughputTokensPerSec)
            .sum();

        return new ClusterStats(
            jobs.size(), running.size(), failed.size(), completedJobs.size(),
            totalFailures.get(), totalRestarts.get(),
            Math.round(avgUtil * 100.0) / 100.0,
            Math.round(totalThroughput)
        );
    }

    // ─── Cluster stats record (Java 21 record) ────────────────────────────────
    public record ClusterStats(
        int totalJobs,
        int runningJobs,
        int failedJobs,
        int completedJobs,
        int totalFailures,
        int totalRestarts,
        double avgGpuUtilization,
        double totalThroughputTokensPerSec
    ) {}
}
