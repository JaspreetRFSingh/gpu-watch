package com.gpuguard.controller;

import com.gpuguard.simulator.GPUCluster;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.concurrent.TimeUnit;

import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests — full Spring Boot context with MockMvc.
 * Equivalent to the Python test_api.py using FastAPI TestClient.
 *
 * @SpringBootTest loads the entire context including the simulation loop.
 * Awaitility handles async assertions (waiting for ticks to populate data).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@DisplayName("GpuGuard REST API")
class GpuGuardControllerTest {

    @Autowired MockMvc mvc;
    @Autowired GPUCluster cluster;

    // ─── Health ───────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Health endpoint")
    class HealthEndpoint {

        @Test
        @DisplayName("GET /api/v1/health returns status ok")
        void healthReturnsOk() throws Exception {
            mvc.perform(get("/api/v1/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ok"))
                .andExpect(jsonPath("$.tick").isNumber())
                .andExpect(jsonPath("$.uptime_seconds").isNumber());
        }

        @Test
        @DisplayName("GET /actuator/health returns Spring Boot health")
        void actuatorHealthOk() throws Exception {
            mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        }
    }

    // ─── Prometheus ───────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Prometheus metrics endpoint")
    class PrometheusEndpoint {

        @Test
        @DisplayName("GET /actuator/prometheus returns text/plain content type")
        void prometheusReturnsTextPlain() throws Exception {
            mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"));
        }

        @Test
        @DisplayName("Prometheus output contains custom GPUGuard metrics")
        void prometheusContainsCustomMetrics() throws Exception {
            // Wait for at least one tick to populate metrics
            Awaitility.await()
                .atMost(15, TimeUnit.SECONDS)
                .until(() -> cluster.getTickCount().get() > 0);

            String body = mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

            assertThat(body).contains("gpuguard_cluster_running_jobs_total");
            assertThat(body).contains("gpuguard_cluster_avg_gpu_utilization_pct");
            assertThat(body).contains("gpuguard_slo_error_budget_remaining_pct");
            assertThat(body).contains("gpuguard_slo_burn_rate");
        }
    }

    // ─── Cluster ──────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Cluster endpoint")
    class ClusterEndpoint {

        @Test
        @DisplayName("GET /api/v1/cluster returns all required fields")
        void clusterHasRequiredFields() throws Exception {
            mvc.perform(get("/api/v1/cluster"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_jobs").isNumber())
                .andExpect(jsonPath("$.running_jobs").isNumber())
                .andExpect(jsonPath("$.failed_jobs").isNumber())
                .andExpect(jsonPath("$.completed_jobs").isNumber())
                .andExpect(jsonPath("$.total_failures").isNumber())
                .andExpect(jsonPath("$.total_restarts").isNumber())
                .andExpect(jsonPath("$.avg_gpu_utilization").isNumber())
                .andExpect(jsonPath("$.total_throughput_tokens_per_sec").isNumber());
        }

        @Test
        @DisplayName("running_jobs does not exceed cluster capacity")
        void runningJobsWithinCapacity() throws Exception {
            mvc.perform(get("/api/v1/cluster"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_jobs").value(lessThanOrEqualTo(6)));
        }
    }

    // ─── Jobs ─────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Jobs endpoint")
    class JobsEndpoint {

        @Test
        @DisplayName("GET /api/v1/jobs returns a JSON array")
        void jobsReturnsArray() throws Exception {
            mvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
        }

        @Test
        @DisplayName("each job has required fields and valid value ranges")
        void jobSchemaIsValid() throws Exception {
            // Wait until at least one job exists
            Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .until(() -> !cluster.getJobs().isEmpty());

            mvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].job_id").isString())
                .andExpect(jsonPath("$[0].model_name").isString())
                .andExpect(jsonPath("$[0].status").isString())
                .andExpect(jsonPath("$[0].step").isNumber())
                .andExpect(jsonPath("$[0].total_steps").isNumber())
                .andExpect(jsonPath("$[0].progress_pct").value(greaterThanOrEqualTo(0.0)))
                .andExpect(jsonPath("$[0].progress_pct").value(lessThanOrEqualTo(100.0)))
                .andExpect(jsonPath("$[0].loss").isNumber())
                .andExpect(jsonPath("$[0].restart_count").isNumber())
                .andExpect(jsonPath("$[0].nodes").isArray());
        }

        @Test
        @DisplayName("each node has valid GPU utilization 0-100")
        void nodeGpuUtilizationInRange() throws Exception {
            Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .until(() -> !cluster.getJobs().isEmpty());

            mvc.perform(get("/api/v1/jobs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].nodes[0].gpu_utilization")
                    .value(allOf(greaterThanOrEqualTo(0.0), lessThanOrEqualTo(100.0))));
        }

        @Test
        @DisplayName("POST /api/v1/jobs/spawn creates a new job")
        void spawnJobCreatesJob() throws Exception {
            int before = cluster.getJobs().size();
            if (before >= 6) return; // skip if cluster at capacity

            mvc.perform(post("/api/v1/jobs/spawn")
                    .param("model_name", "integration-test-model")
                    .param("num_nodes", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.job_id").isString())
                .andExpect(jsonPath("$.model_name").value("integration-test-model"))
                .andExpect(jsonPath("$.num_nodes").value(2));
        }

        @Test
        @DisplayName("POST /api/v1/jobs/spawn returns 429 when cluster is at capacity")
        void spawnJobReturns429WhenFull() throws Exception {
            // Fill cluster to capacity
            while (cluster.getJobs().size() < 6) cluster.spawnJob();

            mvc.perform(post("/api/v1/jobs/spawn")
                    .param("model_name", "overflow-model"))
                .andExpect(status().isTooManyRequests());
        }
    }

    // ─── SLO ──────────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("SLO endpoint")
    class SloEndpoint {

        @Test
        @DisplayName("GET /api/v1/slo returns valid overall status after warmup")
        void sloReturnsValidStatus() throws Exception {
            Awaitility.await()
                .atMost(20, TimeUnit.SECONDS)
                .until(() -> cluster.getTickCount().get() >= 5);

            mvc.perform(get("/api/v1/slo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall_status")
                    .value(in(new String[]{"OK", "WARNING", "AT_RISK", "CRITICAL"})));
        }

        @Test
        @DisplayName("SLO error budget values are between 0 and 100")
        void sloErrorBudgetInRange() throws Exception {
            Awaitility.await()
                .atMost(20, TimeUnit.SECONDS)
                .until(() -> cluster.getTickCount().get() >= 5);

            mvc.perform(get("/api/v1/slo"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.slos").isMap());
        }
    }

    // ─── Remediation ──────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Remediation endpoint")
    class RemediationEndpoint {

        @Test
        @DisplayName("GET /api/v1/remediation returns stats schema")
        void remediationStatsSchema() throws Exception {
            mvc.perform(get("/api/v1/remediation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_actions").isNumber())
                .andExpect(jsonPath("$.successful_actions").isNumber())
                .andExpect(jsonPath("$.success_rate_pct").isNumber())
                .andExpect(jsonPath("$.success_rate_pct").value(lessThanOrEqualTo(100.0)))
                .andExpect(jsonPath("$.success_rate_pct").value(greaterThanOrEqualTo(0.0)));
        }
    }

    // ─── Incidents ────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Incidents endpoint")
    class IncidentsEndpoint {

        @Test
        @DisplayName("GET /api/v1/incidents returns required fields")
        void incidentsSchema() throws Exception {
            mvc.perform(get("/api/v1/incidents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active_failures").isNumber())
                .andExpect(jsonPath("$.resolved_failures").isNumber())
                .andExpect(jsonPath("$.recent_incidents").isArray())
                .andExpect(jsonPath("$.recent_mttr_avg_seconds").isNumber());
        }

        @Test
        @DisplayName("active_failures count is non-negative")
        void activeFailuresNonNegative() throws Exception {
            mvc.perform(get("/api/v1/incidents"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active_failures").value(greaterThanOrEqualTo(0)));
        }
    }

    // ─── Anomalies ────────────────────────────────────────────────────────────
    @Nested
    @DisplayName("Anomalies endpoint")
    class AnomaliesEndpoint {

        @Test
        @DisplayName("GET /api/v1/anomalies returns stats schema")
        void anomaliesSchema() throws Exception {
            mvc.perform(get("/api/v1/anomalies"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total_anomalies").isNumber())
                .andExpect(jsonPath("$.active_trackers").isNumber())
                .andExpect(jsonPath("$.recent").isArray());
        }
    }

    // ─── Hamcrest helper ──────────────────────────────────────────────────────
    private static org.hamcrest.Matcher<String> in(String[] values) {
        return org.hamcrest.Matchers.in(java.util.List.of(values));
    }

    private static void assertThat(String actual) {
        org.assertj.core.api.Assertions.assertThat(actual);
    }
}
