package com.gpuguard.anomaly;

import com.gpuguard.simulator.GPUCluster;
import com.gpuguard.simulator.JobStatus;
import com.gpuguard.simulator.TrainingJob;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Statistical anomaly detection for GPU training metrics.
 * Three algorithms run in parallel per metric:
 *   1. Z-Score  — sudden spikes (gradient explosion, OOM precursor)
 *   2. CUSUM    — gradual drift (straggler degradation, throughput decay)
 *   3. IQR      — robust outlier detection for skewed distributions
 *
 * No ML framework required. Equivalent to Python anomaly_detection.py.
 */
@Slf4j
public class AnomalyDetectionEngine {

    private final Map<String, MetricTracker> jobTrackers = new ConcurrentHashMap<>();
    private final Map<String, MetricTracker> nodeTrackers = new ConcurrentHashMap<>();
    private final List<AnomalyEvent> anomalyLog = new CopyOnWriteArrayList<>();
    private int totalAnomalies = 0;

    public List<AnomalyEvent> evaluate(GPUCluster cluster) {
        List<AnomalyEvent> allAnomalies = new ArrayList<>();
        Set<String> activeJobIds = cluster.getJobs().keySet();

        for (TrainingJob job : cluster.getJobs().values()) {
            if (job.getStatus() != JobStatus.RUNNING) continue;

            // Job-level metrics
            allAnomalies.addAll(evaluateMetric(jobTrackers,
                job.getJobId() + ":training_loss", "training_loss", job.getJobId(), job.getLoss()));
            allAnomalies.addAll(evaluateMetric(jobTrackers,
                job.getJobId() + ":throughput", "throughput_tokens_per_sec", job.getJobId(), job.getThroughputTokensPerSec()));

            // Node-level metrics
            for (var node : job.getNodes()) {
                if (!node.isHealthy()) continue;
                String prefix = node.getNodeId();
                allAnomalies.addAll(evaluateMetric(nodeTrackers,
                    prefix + ":gpu_util", "gpu_utilization_pct", node.getNodeId(), node.getGpuUtilization()));
                allAnomalies.addAll(evaluateMetric(nodeTrackers,
                    prefix + ":gpu_mem", "gpu_memory_used_gb", node.getNodeId(), node.getGpuMemoryUsedGb()));
                allAnomalies.addAll(evaluateMetric(nodeTrackers,
                    prefix + ":gpu_temp", "gpu_temperature_celsius", node.getNodeId(), node.getGpuTempCelsius()));
            }
        }

        // Evict trackers for inactive jobs
        jobTrackers.keySet().removeIf(k -> activeJobIds.stream().noneMatch(k::startsWith));

        anomalyLog.addAll(allAnomalies);
        totalAnomalies += allAnomalies.size();
        return allAnomalies;
    }

    private List<AnomalyEvent> evaluateMetric(Map<String, MetricTracker> trackers,
                                               String key, String metricName,
                                               String entityId, double value) {
        MetricTracker tracker = trackers.computeIfAbsent(key,
            k -> new MetricTracker(metricName, entityId, 60));
        List<AnomalyEvent> anomalies = tracker.evaluate(value);
        anomalies.forEach(a -> log.warn("[ANOMALY] {} on {}: {}", a.metric(), a.entityId(), a.description()));
        return anomalies;
    }

    public AnomalyStats getStats() {
        Map<String, Integer> byType = new LinkedHashMap<>();
        for (AnomalyEvent e : anomalyLog) {
            byType.merge(e.anomalyType(), 1, Integer::sum);
        }
        List<AnomalyEvent> recent = anomalyLog.stream()
            .skip(Math.max(0, anomalyLog.size() - 20))
            .sorted(Comparator.comparing(AnomalyEvent::timestamp).reversed())
            .toList();

        return new AnomalyStats(totalAnomalies, byType,
            jobTrackers.size() + nodeTrackers.size(), recent);
    }

    public record AnomalyStats(
        int totalAnomalies,
        Map<String, Integer> byType,
        int activeTrackers,
        List<AnomalyEvent> recent
    ) {}
}
