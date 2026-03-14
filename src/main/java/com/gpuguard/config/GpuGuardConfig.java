package com.gpuguard.config;

import com.gpuguard.anomaly.AnomalyDetectionEngine;
import com.gpuguard.remediation.AutoRemediationEngine;
import com.gpuguard.simulator.GPUCluster;
import com.gpuguard.slo.SLOEngine;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring @Configuration — wires all simulation components.
 * Equivalent to module-level instantiation in Python main.py.
 *
 * Using @Bean rather than @Component on the simulation classes
 * keeps them as plain Java objects (no Spring dependency in core logic),
 * which makes unit testing much simpler.
 */
@Configuration
public class GpuGuardConfig {

    @Bean
    public GPUCluster gpuCluster() {
        return new GPUCluster(6); // max 6 concurrent jobs
    }

    @Bean
    public SLOEngine sloEngine() {
        return new SLOEngine();
    }

    @Bean
    public AutoRemediationEngine autoRemediationEngine() {
        return new AutoRemediationEngine();
    }

    @Bean
    public AnomalyDetectionEngine anomalyDetectionEngine() {
        return new AnomalyDetectionEngine();
    }
}
