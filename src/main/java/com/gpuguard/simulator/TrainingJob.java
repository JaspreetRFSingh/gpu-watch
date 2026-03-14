package com.gpuguard.simulator;

import lombok.Data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Represents a single LLM training job running on the cluster.
 * Equivalent to the Python TrainingJob dataclass.
 *
 * Java 21 note: This could be a Record but we need mutability for simulation.
 * Using Lombok @Data instead for clean getters/setters/toString.
 */
@Data
public class TrainingJob {

    private static final Random RNG = new Random();
    private static final double BASELINE_TOKENS_PER_SEC_PER_GPU = 125_000.0;

    private final String jobId;
    private final String modelName;
    private final int numNodes;

    private JobStatus status = JobStatus.QUEUED;
    private int step = 0;
    private int totalSteps;
    private double loss = 10.0;
    private double throughputTokensPerSec = 0.0;
    private long elapsedSeconds = 0L;
    private FailureType failureType = FailureType.NONE;
    private int restartCount = 0;
    private int maxRestarts = 3;
    private int checkpointStep = 0;
    private List<GPUNode> nodes;
    private Instant creationTime = Instant.now();

    public TrainingJob(String modelName, int numNodes, int totalSteps) {
        this.jobId = UUID.randomUUID().toString().substring(0, 8);
        this.modelName = modelName;
        this.numNodes = numNodes;
        this.totalSteps = totalSteps;
        this.nodes = new ArrayList<>();
        for (int i = 0; i < numNodes; i++) {
            nodes.add(new GPUNode("node-" + jobId + "-" + i));
        }
    }

    public double getProgressPct() {
        return totalSteps > 0 ? (double) step / totalSteps * 100.0 : 0.0;
    }

    /**
     * Realistic loss curve: exponential decay with Gaussian noise and occasional spikes.
     */
    public double simulateLoss() {
        double base = 10.0 * Math.exp(-0.004 * step);
        double noise = RNG.nextGaussian() * 0.05;
        double spike = RNG.nextDouble() < 0.02 ? 0.3 : 0.0;
        return Math.max(0.1, base + noise + spike);
    }

    /**
     * Token throughput scales with healthy node count and collective efficiency.
     */
    public double simulateThroughput() {
        long healthyNodes = nodes.stream().filter(GPUNode::isHealthy).count();
        if (healthyNodes == 0) return 0.0;
        double base = BASELINE_TOKENS_PER_SEC_PER_GPU * numNodes * 8;
        double efficiency = healthyNodes == numNodes ? 0.85 : 0.40;
        double jitter = 1.0 + RNG.nextGaussian() * 0.03;
        return base * efficiency * jitter;
    }
}
