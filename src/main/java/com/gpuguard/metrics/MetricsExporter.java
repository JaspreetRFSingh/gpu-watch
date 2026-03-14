package com.gpuguard.metrics;

import com.gpuguard.simulator.GPUCluster;
import com.gpuguard.simulator.GPUNode;
import com.gpuguard.simulator.JobStatus;
import com.gpuguard.simulator.TrainingJob;
import com.gpuguard.slo.SLOEngine;
import com.gpuguard.slo.SLOWindow;
import io.micrometer.core.instrument.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Exports GPU cluster metrics via Micrometer → Prometheus.
 *
 * Micrometer replaces Python's prometheus_client.
 * Metrics are exposed at /actuator/prometheus (configured in application.yml).
 *
 * Key difference from Python:
 *   Python: Gauge.labels(...).set(value)
 *   Java:   registry.gauge("name", tags, supplier)
 *           or MultiGauge for dynamic label sets
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MetricsExporter {

    private final MeterRegistry registry;
    private final GPUCluster cluster;
    private final SLOEngine sloEngine;

    // Track previously registered gauges to avoid duplicate registration
    private final Map<String, AtomicReference<Double>> gaugeValues = new ConcurrentHashMap<>();

    @PostConstruct
    public void registerStaticMetrics() {
        // Cluster-level static gauges (labels don't change)
        registerGauge("gpuguard_cluster_running_jobs_total",    "Number of running training jobs",    Tags.empty());
        registerGauge("gpuguard_cluster_failed_jobs_total",     "Number of currently failed jobs",    Tags.empty());
        registerGauge("gpuguard_cluster_avg_gpu_utilization_pct","Avg GPU utilization across jobs",   Tags.empty());
        registerGauge("gpuguard_cluster_total_throughput_tokens_per_sec", "Total token throughput",   Tags.empty());

        // Counters
        Counter.builder("gpuguard_cluster_failure_events_total")
            .description("Total failure events")
            .tag("failure_type", "any")
            .register(registry);

        Counter.builder("gpuguard_cluster_restart_events_total")
            .description("Total auto-remediation restarts")
            .register(registry);
    }

    @Scheduled(fixedRate = 5000)
    public void updateMetrics() {
        updateClusterMetrics();
        updateJobMetrics();
        updateSloMetrics();
    }

    private void updateClusterMetrics() {
        GPUCluster.ClusterStats stats = cluster.getStats();
        setGauge("gpuguard_cluster_running_jobs_total",             Tags.empty(), stats.runningJobs());
        setGauge("gpuguard_cluster_failed_jobs_total",              Tags.empty(), stats.failedJobs());
        setGauge("gpuguard_cluster_avg_gpu_utilization_pct",        Tags.empty(), stats.avgGpuUtilization());
        setGauge("gpuguard_cluster_total_throughput_tokens_per_sec",Tags.empty(), stats.totalThroughputTokensPerSec());
    }

    private void updateJobMetrics() {
        for (TrainingJob job : cluster.getJobs().values()) {
            Tags jobTags = Tags.of("job_id", job.getJobId(), "model_name", job.getModelName());

            setGauge("gpuguard_job_training_loss",             jobTags, job.getLoss());
            setGauge("gpuguard_job_training_step",             jobTags, job.getStep());
            setGauge("gpuguard_job_progress_pct",              jobTags, job.getProgressPct());
            setGauge("gpuguard_job_throughput_tokens_per_sec", jobTags, job.getThroughputTokensPerSec());
            setGauge("gpuguard_job_restart_count",             jobTags, job.getRestartCount());

            for (GPUNode node : job.getNodes()) {
                Tags nodeTags = Tags.of("node_id", node.getNodeId(), "job_id", job.getJobId());

                setGauge("gpuguard_node_gpu_utilization_pct",        nodeTags, node.getGpuUtilization());
                setGauge("gpuguard_node_gpu_memory_used_gb",         nodeTags, node.getGpuMemoryUsedGb());
                setGauge("gpuguard_node_gpu_memory_utilization_pct", nodeTags, node.getGpuMemoryUtilization());
                setGauge("gpuguard_node_gpu_temp_celsius",           nodeTags, node.getGpuTempCelsius());
                setGauge("gpuguard_node_nvlink_bandwidth_gbps",      nodeTags, node.getNvlinkBandwidthGbps());
                setGauge("gpuguard_node_healthy",                    nodeTags, node.isHealthy() ? 1.0 : 0.0);
            }
        }
    }

    private void updateSloMetrics() {
        for (Map.Entry<String, SLOWindow> entry : sloEngine.getAllSLOs().entrySet()) {
            Tags sloTags = Tags.of("slo_name", entry.getKey());
            SLOWindow slo = entry.getValue();
            setGauge("gpuguard_slo_error_budget_remaining_pct", sloTags, slo.errorBudgetRemainingPct());
            setGauge("gpuguard_slo_burn_rate",                  sloTags, slo.burnRate());
        }
    }

    /**
     * Register a gauge backed by an AtomicReference<Double>.
     * Micrometer will call the supplier lazily when scraped.
     */
    private void registerGauge(String name, String description, Tags tags) {
        String key = name + tags;
        AtomicReference<Double> ref = new AtomicReference<>(0.0);
        gaugeValues.put(key, ref);
        Gauge.builder(name, ref, AtomicReference::get)
            .description(description)
            .tags(tags)
            .register(registry);
    }

    private void setGauge(String name, Tags tags, double value) {
        String key = name + tags;
        AtomicReference<Double> ref = gaugeValues.computeIfAbsent(key, k -> {
            AtomicReference<Double> newRef = new AtomicReference<>(0.0);
            Gauge.builder(name, newRef, AtomicReference::get)
                .tags(tags)
                .register(registry);
            return newRef;
        });
        ref.set(value);
    }
}
