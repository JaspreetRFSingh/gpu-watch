package com.gpuguard.simulator;

import lombok.Data;

import java.util.Random;

/**
 * Models a single GPU node in the training cluster.
 * Equivalent to the Python GPUNode dataclass.
 */
@Data
public class GPUNode {

    private static final Random RNG = new Random();
    private static final double GPU_MEMORY_TOTAL_GB = 80.0; // H100 SXM5

    private final String nodeId;
    private final int gpuCount;

    private double gpuUtilization = 0.0;
    private double gpuMemoryUsedGb = 0.0;
    private double gpuTempCelsius = 40.0;
    private double nvlinkBandwidthGbps = 0.0;
    private double pcieBandwidthGbps = 0.0;
    private boolean healthy = true;
    private FailureType failureType = FailureType.NONE;
    private int flapCount = 0;

    public GPUNode(String nodeId) {
        this.nodeId = nodeId;
        this.gpuCount = 8;
    }

    public double getGpuMemoryTotalGb() {
        return GPU_MEMORY_TOTAL_GB;
    }

    public double getGpuMemoryUtilization() {
        return (gpuMemoryUsedGb / GPU_MEMORY_TOTAL_GB) * 100.0;
    }

    public void simulateTick(boolean jobRunning) {
        if (!jobRunning || !healthy) {
            gpuUtilization = Math.max(0, gpuUtilization - (5 + RNG.nextDouble() * 15));
            gpuMemoryUsedGb = Math.max(0, gpuMemoryUsedGb - (1 + RNG.nextDouble() * 4));
            nvlinkBandwidthGbps = 0.0;
            gpuTempCelsius = Math.max(35, gpuTempCelsius - (1 + RNG.nextDouble() * 2));
            return;
        }

        switch (failureType) {
            case OOM -> {
                gpuMemoryUsedGb = Math.min(GPU_MEMORY_TOTAL_GB, gpuMemoryUsedGb + (2 + RNG.nextDouble() * 6));
                gpuUtilization = 85 + RNG.nextDouble() * 10;
            }
            case SLOW_NODE -> {
                gpuUtilization = 20 + RNG.nextDouble() * 20;
                gpuMemoryUsedGb = 30 + RNG.nextDouble() * 20;
            }
            default -> {
                // Normal training — high utilization with realistic jitter
                gpuUtilization = clamp(gauss(92, 3), 0, 100);
                gpuMemoryUsedGb = 55 + RNG.nextDouble() * 17;
                nvlinkBandwidthGbps = gauss(450, 20);   // NVLink 4.0
                pcieBandwidthGbps = gauss(32, 2);
                gpuTempCelsius = gauss(78, 3);
            }
        }
    }

    private static double gauss(double mean, double std) {
        return mean + RNG.nextGaussian() * std;
    }

    private static double clamp(double val, double min, double max) {
        return Math.max(min, Math.min(max, val));
    }
}
