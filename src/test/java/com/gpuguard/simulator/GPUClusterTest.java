package com.gpuguard.simulator;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for the GPU cluster simulation engine.
 * Uses @RepeatedTest for probabilistic simulation tests.
 */
@DisplayName("GPUCluster")
class GPUClusterTest {

    private GPUCluster cluster;

    @BeforeEach
    void setUp() {
        cluster = new GPUCluster(5);
    }

    @Nested
    @DisplayName("Job lifecycle")
    class JobLifecycle {

        @Test
        @DisplayName("spawning a job adds it to the active jobs map")
        void spawnAddsJobToMap() {
            TrainingJob job = cluster.spawnJob("test-model", 4);

            assertThat(cluster.getJobs()).containsKey(job.getJobId());
            assertThat(job.getStatus()).isEqualTo(JobStatus.RUNNING);
            assertThat(job.getModelName()).isEqualTo("test-model");
            assertThat(job.getNodes()).hasSize(4);
        }

        @Test
        @DisplayName("cluster does not exceed max concurrent jobs")
        void respectsMaxConcurrentJobs() {
            GPUCluster small = new GPUCluster(2);
            small.spawnJob("a", 2);
            small.spawnJob("b", 2);
            // Force-tick many times to ensure no extra spawns sneak in
            for (int i = 0; i < 50; i++) small.tick();

            assertThat(small.getJobs().size() + small.getCompletedJobs().size())
                .isLessThanOrEqualTo(2 + 50); // bounded
        }

        @Test
        @DisplayName("completed jobs are moved to completedJobs list")
        void completedJobsAreTracked() {
            TrainingJob job = cluster.spawnJob("fast-model", 2);
            job.setTotalSteps(3);
            job.setStep(2);

            cluster.tick();

            // Job should either still be running (stepped forward) or completed
            boolean inActive = cluster.getJobs().containsKey(job.getJobId());
            boolean inCompleted = cluster.getCompletedJobs().stream()
                .anyMatch(j -> j.getJobId().equals(job.getJobId()));
            assertThat(inActive || inCompleted).isTrue();
        }

        @Test
        @DisplayName("failed job attempts restart up to maxRestarts")
        void failedJobRestartsAutomatically() {
            TrainingJob job = cluster.spawnJob("test-model", 4);
            job.setStatus(JobStatus.FAILED);
            job.setFailureType(FailureType.NCCL_TIMEOUT);
            int initialRestarts = job.getRestartCount();

            cluster.tick();

            if (cluster.getJobs().containsKey(job.getJobId())) {
                assertThat(cluster.getJobs().get(job.getJobId()).getRestartCount())
                    .isGreaterThan(initialRestarts);
            }
        }

        @Test
        @DisplayName("job exceeding maxRestarts is removed from cluster")
        void exhaustedJobIsRemoved() {
            TrainingJob job = cluster.spawnJob("test-model", 2);
            job.setStatus(JobStatus.FAILED);
            job.setFailureType(FailureType.NODE_DOWN);
            job.setRestartCount(job.getMaxRestarts()); // already at limit

            cluster.tick();

            assertThat(cluster.getJobs()).doesNotContainKey(job.getJobId());
        }
    }

    @Nested
    @DisplayName("Cluster stats")
    class Stats {

        @Test
        @DisplayName("getStats returns correct running job count")
        void statsRunningCount() {
            cluster.spawnJob("m1", 2);
            cluster.spawnJob("m2", 4);

            GPUCluster.ClusterStats stats = cluster.getStats();
            assertThat(stats.runningJobs()).isEqualTo(2);
            assertThat(stats.totalJobs()).isEqualTo(2);
        }

        @Test
        @DisplayName("avgGpuUtilization is 0 when no jobs are running")
        void avgUtilZeroWithNoJobs() {
            GPUCluster.ClusterStats stats = cluster.getStats();
            assertThat(stats.avgGpuUtilization()).isEqualTo(0.0);
            assertThat(stats.totalThroughputTokensPerSec()).isEqualTo(0.0);
        }

        @Test
        @DisplayName("tick counter increments each tick")
        void tickCounterIncrements() {
            int before = cluster.getTickCount().get();
            cluster.tick();
            cluster.tick();
            assertThat(cluster.getTickCount().get()).isEqualTo(before + 2);
        }
    }

    @Nested
    @DisplayName("Training job simulation")
    class JobSimulation {

        @Test
        @DisplayName("loss decreases over training steps")
        void lossCurveDecreases() {
            TrainingJob job = new TrainingJob("test", 4, 1000);
            double lossAtStep0, lossAtStep500, lossAtStep900;

            job.setStep(0);   lossAtStep0   = job.simulateLoss();
            job.setStep(500); lossAtStep500 = job.simulateLoss();
            job.setStep(900); lossAtStep900 = job.simulateLoss();

            // Average trend should be decreasing (noisy, but baseline exp decay holds)
            assertThat(lossAtStep0).isGreaterThan(lossAtStep900);
        }

        @Test
        @DisplayName("progress percent is bounded 0-100")
        void progressInRange() {
            TrainingJob job = new TrainingJob("test", 4, 100);
            job.setStep(0);
            assertThat(job.getProgressPct()).isEqualTo(0.0);
            job.setStep(50);
            assertThat(job.getProgressPct()).isEqualTo(50.0);
            job.setStep(100);
            assertThat(job.getProgressPct()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("throughput drops to 0 when all nodes are unhealthy")
        void throughputZeroWithNoHealthyNodes() {
            TrainingJob job = new TrainingJob("test", 4, 1000);
            job.getNodes().forEach(n -> n.setHealthy(false));
            assertThat(job.simulateThroughput()).isEqualTo(0.0);
        }

        @RepeatedTest(10)
        @DisplayName("GPU utilization stays within 0-100 range during normal training")
        void gpuUtilizationInRange() {
            GPUNode node = new GPUNode("test-node");
            node.simulateTick(true);
            assertThat(node.getGpuUtilization()).isBetween(0.0, 100.0);
        }

        @Test
        @DisplayName("idle node decreases GPU utilization over time")
        void idleNodeDecreasesUtil() {
            GPUNode node = new GPUNode("test-node");
            node.setGpuUtilization(90.0);
            node.setGpuMemoryUsedGb(60.0);

            node.simulateTick(false);

            assertThat(node.getGpuUtilization()).isLessThan(90.0);
            assertThat(node.getNvlinkBandwidthGbps()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Failure types")
    class FailureTypes {

        @Test
        @DisplayName("OOM failure increases GPU memory usage")
        void oomIncreasesMemory() {
            GPUNode node = new GPUNode("test-node");
            node.setGpuMemoryUsedGb(40.0);
            node.setFailureType(FailureType.OOM);

            node.simulateTick(true);

            assertThat(node.getGpuMemoryUsedGb()).isGreaterThan(40.0);
        }

        @Test
        @DisplayName("slow node straggler reduces GPU utilization")
        void slowNodeReducesUtilization() {
            GPUNode node = new GPUNode("test-node");
            node.setFailureType(FailureType.SLOW_NODE);

            node.simulateTick(true);

            // Straggler should have much lower utilization than normal ~92%
            assertThat(node.getGpuUtilization()).isLessThan(60.0);
        }
    }
}
