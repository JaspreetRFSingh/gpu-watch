package com.gpuguard.anomaly;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Tracks one metric time series for one entity.
 * Runs Z-score, CUSUM, and IQR detection in parallel.
 *
 * Welford's online algorithm for rolling mean/variance: O(1) per update,
 * no need to store the full history window in memory.
 */
public class MetricTracker {

    private static final int WARMUP_TICKS = 20;
    private static final double Z_SCORE_THRESHOLD = 3.0;
    private static final double Z_SCORE_CRITICAL = 4.5;
    private static final double IQR_MULTIPLIER = 2.5;
    private static final double CUSUM_K = 0.5;
    private static final double CUSUM_H = 5.0;

    private final String metricName;
    private final String entityId;
    private final int window;

    // Welford rolling stats
    private final Deque<Double> valueWindow;
    private int n = 0;
    private double mean = 0.0;
    private double m2 = 0.0;   // sum of squared deviations

    // CUSUM state
    private double sPos = 0.0;
    private double sNeg = 0.0;

    private int tickCount = 0;

    public MetricTracker(String metricName, String entityId, int window) {
        this.metricName = metricName;
        this.entityId = entityId;
        this.window = window;
        this.valueWindow = new ArrayDeque<>(window);
    }

    public List<AnomalyEvent> evaluate(double value) {
        tickCount++;
        updateStats(value);
        valueWindow.addLast(value);
        if (valueWindow.size() > window) valueWindow.pollFirst();

        if (tickCount < WARMUP_TICKS) return List.of();

        List<AnomalyEvent> anomalies = new ArrayList<>();
        double z = zScore(value);

        // 1. Z-Score spike detection
        if (Math.abs(z) >= Z_SCORE_THRESHOLD) {
            String severity = Math.abs(z) >= Z_SCORE_CRITICAL ? "critical" : "warning";
            String direction = z > 0 ? "above" : "below";
            anomalies.add(new AnomalyEvent(
                metricName, entityId, "spike", value, mean, severity,
                Instant.now(),
                String.format("Value %s expected by %.1fσ (z=%.2f)", direction, Math.abs(z), z)
            ));
        }

        // 2. CUSUM drift detection
        sPos = Math.max(0.0, sPos + z - CUSUM_K);
        sNeg = Math.max(0.0, sNeg - z - CUSUM_K);

        if (sPos >= CUSUM_H) {
            anomalies.add(new AnomalyEvent(
                metricName, entityId, "drift_high", value, mean, "warning",
                Instant.now(), String.format("Sustained upward drift (CUSUM S+=%.2f)", sPos)
            ));
            sPos = 0.0; sNeg = 0.0; // reset after alarm
        } else if (sNeg >= CUSUM_H) {
            anomalies.add(new AnomalyEvent(
                metricName, entityId, "drift_low", value, mean, "warning",
                Instant.now(), String.format("Sustained downward drift (CUSUM S-=%.2f)", sNeg)
            ));
            sPos = 0.0; sNeg = 0.0;
        }

        // 3. IQR outlier detection (skip if already caught by z-score)
        if (valueWindow.size() >= 20 && anomalies.stream().noneMatch(a -> "spike".equals(a.anomalyType()))) {
            double[] sorted = valueWindow.stream().mapToDouble(Double::doubleValue).sorted().toArray();
            int nv = sorted.length;
            double q1 = sorted[nv / 4];
            double q3 = sorted[(3 * nv) / 4];
            double iqr = q3 - q1;
            if (iqr > 0) {
                double lower = q1 - IQR_MULTIPLIER * iqr;
                double upper = q3 + IQR_MULTIPLIER * iqr;
                if (value < lower || value > upper) {
                    anomalies.add(new AnomalyEvent(
                        metricName, entityId, "outlier", value, (q1 + q3) / 2, "warning",
                        Instant.now(),
                        String.format("IQR outlier: value=%.2f, fence=[%.2f, %.2f]", value, lower, upper)
                    ));
                }
            }
        }

        return anomalies;
    }

    // ─── Welford's online algorithm ───────────────────────────────────────────

    private void updateStats(double x) {
        if (valueWindow.size() == window) {
            // Remove oldest value (approximate — Welford doesn't support true removal)
            double old = valueWindow.peekFirst();
            if (n > 1) {
                double oldMean = mean;
                n--;
                mean = (mean * (n + 1) - old) / n;
                m2 = Math.max(0, m2 - (old - oldMean) * (old - mean));
            } else {
                n = 0; mean = 0.0; m2 = 0.0;
            }
        }
        n++;
        double delta = x - mean;
        mean += delta / n;
        double delta2 = x - mean;
        m2 += delta * delta2;
    }

    private double variance() {
        return n > 1 ? m2 / n : 0.0;
    }

    private double std() {
        return Math.sqrt(variance());
    }

    private double zScore(double x) {
        double s = std();
        return s == 0 ? 0.0 : (x - mean) / s;
    }
}
