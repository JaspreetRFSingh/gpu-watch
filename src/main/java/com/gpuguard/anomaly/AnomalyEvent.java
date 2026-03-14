package com.gpuguard.anomaly;

import java.time.Instant;

/**
 * Immutable anomaly event. Java 21 record replaces Python @dataclass.
 */
public record AnomalyEvent(
    String metric,
    String entityId,
    String anomalyType,   // spike | drift_high | drift_low | outlier
    double value,
    double expected,
    String severity,      // warning | critical
    Instant timestamp,
    String description
) {}
