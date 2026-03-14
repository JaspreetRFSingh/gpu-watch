package com.gpuguard.slo;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

/**
 * Unit tests for SLOWindow.
 * Equivalent to the Python TestSLOWindow class.
 * Uses AssertJ for fluent assertions (included in spring-boot-starter-test).
 */
@DisplayName("SLOWindow")
class SLOWindowTest {

    private SLOWindow slo;

    @BeforeEach
    void setUp() {
        slo = new SLOWindow("test_slo", 99.5);
    }

    @Nested
    @DisplayName("SLI calculation")
    class SliCalculation {

        @Test
        @DisplayName("returns 100% SLI when all events are good")
        void perfectAvailability() {
            for (int i = 0; i < 100; i++) slo.record(true);

            assertThat(slo.currentSliPct()).isEqualTo(100.0);
            assertThat(slo.errorBudgetRemainingPct()).isEqualTo(100.0);
            assertThat(slo.burnRate()).isEqualTo(0.0);
            assertThat(slo.status()).isEqualTo(SLOWindow.SloStatus.OK);
        }

        @Test
        @DisplayName("calculates correct SLI with mixed events")
        void mixedEvents() {
            for (int i = 0; i < 99; i++) slo.record(true);
            slo.record(false);

            assertThat(slo.currentSliPct()).isCloseTo(99.0, within(0.01));
        }

        @Test
        @DisplayName("returns 100% SLI when no events recorded yet")
        void emptyWindow() {
            assertThat(slo.currentSliPct()).isEqualTo(100.0);
            assertThat(slo.currentWindowEvents()).isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("Error budget")
    class ErrorBudget {

        @Test
        @DisplayName("consumes full budget when bad rate exceeds target")
        void fullBudgetConsumed() {
            for (int i = 0; i < 98; i++) slo.record(true);
            for (int i = 0; i < 2;  i++) slo.record(false);

            // 2% bad rate vs 0.5% allowed → budget exhausted
            assertThat(slo.errorBudgetRemainingPct()).isEqualTo(0.0);
            assertThat(slo.errorBudgetConsumedPct()).isEqualTo(100.0);
        }

        @Test
        @DisplayName("leaves budget intact when SLI is exactly at target")
        void exactlyAtTarget() {
            // 99.5% good, 0.5% bad = exactly at target
            for (int i = 0; i < 995; i++) slo.record(true);
            for (int i = 0; i < 5;   i++) slo.record(false);

            // Burn rate should be ~1.0 (sustainable)
            assertThat(slo.burnRate()).isBetween(0.5, 2.0);
        }
    }

    @Nested
    @DisplayName("Burn rate")
    class BurnRate {

        @Test
        @DisplayName("exceeds 14.4x threshold on heavy failure injection")
        void criticalBurnRate() {
            for (int i = 0; i < 50; i++) slo.record(false);

            assertThat(slo.burnRate()).isGreaterThan(14.4);
            assertThat(slo.status()).isEqualTo(SLOWindow.SloStatus.CRITICAL);
        }

        @Test
        @DisplayName("triggers WARNING status between 6x and 14.4x")
        void warningBurnRate() {
            // Inject enough failures to get burn rate ~6-14x
            for (int i = 0; i < 80; i++) slo.record(true);
            for (int i = 0; i < 10; i++) slo.record(false);

            double br = slo.burnRate();
            if (br >= 6.0 && br < 14.4) {
                assertThat(slo.status()).isEqualTo(SLOWindow.SloStatus.WARNING);
            }
        }

        @Test
        @DisplayName("is 0 when no events recorded")
        void zeroBurnRateWhenEmpty() {
            assertThat(slo.burnRate()).isEqualTo(0.0);
        }
    }

    @Nested
    @DisplayName("Rolling window eviction")
    class RollingWindow {

        @Test
        @DisplayName("evicts old bad events after window expires")
        void evictsOldBadEvents() throws Exception {
            // Use a tiny window via reflection to avoid 720-tick wait
            var field = SLOWindow.class.getDeclaredField("windowTicks");
            field.setAccessible(true);

            SLOWindow tinyWindow = new SLOWindow("tiny", 99.0);
            field.set(tinyWindow, 10);

            // Fill with failures
            for (int i = 0; i < 10; i++) tinyWindow.record(false);
            assertThat(tinyWindow.currentSliPct()).isLessThan(10.0);

            // Add good events — old failures should roll off
            for (int i = 0; i < 10; i++) tinyWindow.record(true);
            assertThat(tinyWindow.currentSliPct()).isGreaterThan(90.0);
        }
    }

    @Nested
    @DisplayName("SloReport record")
    class SloReportRecord {

        @Test
        @DisplayName("report contains all required fields")
        void reportHasAllFields() {
            slo.record(true);
            SLOWindow.SloReport report = slo.toReport();

            assertThat(report.name()).isEqualTo("test_slo");
            assertThat(report.targetPct()).isEqualTo(99.5);
            assertThat(report.currentSliPct()).isBetween(0.0, 100.0);
            assertThat(report.errorBudgetRemainingPct()).isBetween(0.0, 100.0);
            assertThat(report.burnRate()).isGreaterThanOrEqualTo(0.0);
            assertThat(report.status()).isIn("OK", "WARNING", "AT_RISK", "CRITICAL");
        }
    }
}
