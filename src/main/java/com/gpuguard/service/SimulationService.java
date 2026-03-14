package com.gpuguard.service;

import com.gpuguard.anomaly.AnomalyDetectionEngine;
import com.gpuguard.remediation.AutoRemediationEngine;
import com.gpuguard.simulator.GPUCluster;
import com.gpuguard.slo.SLOEngine;
import com.gpuguard.slo.SLOEngine.SloEngineReport;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Wires together the simulation loop using Spring's @Scheduled.
 * Replaces the Python threading.Thread background loop.
 *
 * Loop: simulate → evaluate SLOs → auto-remediate → detect anomalies
 * Runs every 5 seconds (fixedRate = 5000ms).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SimulationService {

    private final GPUCluster cluster;
    private final SLOEngine sloEngine;
    private final AutoRemediationEngine remediationEngine;
    private final AnomalyDetectionEngine anomalyEngine;

    @Getter
    private final AtomicReference<SloEngineReport> latestSloReport = new AtomicReference<>();

    @PostConstruct
    public void seedInitialJobs() {
        for (int i = 0; i < 3; i++) {
            cluster.spawnJob();
        }
        log.info("GPUGuard simulation started with {} initial jobs", 3);
    }

    @Scheduled(fixedRate = 5000)
    public void tick() {
        try {
            cluster.tick();
            SloEngineReport report = sloEngine.evaluate(cluster);
            latestSloReport.set(report);
            remediationEngine.evaluate(cluster, report);
            anomalyEngine.evaluate(cluster);
        } catch (Exception e) {
            log.error("Simulation tick failed", e);
        }
    }
}
