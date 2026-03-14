package com.gpuguard.slo;

import lombok.Getter;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Rolling window SLO tracker.
 * Implements Google SRE Book error budget and burn rate methodology.
 *
 * Equivalent to the Python SLOWindow class.
 * Uses ArrayDeque for O(1) FIFO eviction of stale ticks.
 */
public class SLOWindow {

    private static final int TICK_INTERVAL_SECONDS = 5;
    private static final int WINDOW_SECONDS = 3600; // 1-hour rolling window

    @Getter private final String name;
    @Getter private final double targetPct;
    private final int windowTicks;

    private final Deque<Integer> goodTicks = new ArrayDeque<>();
    private final Deque<Integer> badTicks = new ArrayDeque<>();
    private int tickCounter = 0;
    @Getter private int totalEvents = 0;

    public SLOWindow(String name, double targetPct) {
        this.name = name;
        this.targetPct = targetPct;
        this.windowTicks = WINDOW_SECONDS / TICK_INTERVAL_SECONDS; // 720
    }

    public void record(boolean isGood) {
        tickCounter++;
        totalEvents++;

        if (isGood) goodTicks.addLast(tickCounter);
        else         badTicks.addLast(tickCounter);

        // Evict ticks outside rolling window
        int cutoff = tickCounter - windowTicks;
        while (!goodTicks.isEmpty() && goodTicks.peekFirst() <= cutoff) goodTicks.pollFirst();
        while (!badTicks.isEmpty()  && badTicks.peekFirst()  <= cutoff) badTicks.pollFirst();
    }

    public int currentWindowEvents() {
        return goodTicks.size() + badTicks.size();
    }

    /** Current SLI: good events / total events in window (%). */
    public double currentSliPct() {
        int total = currentWindowEvents();
        if (total == 0) return 100.0;
        return (double) goodTicks.size() / total * 100.0;
    }

    /** Total allowed error budget = 100% - target%. */
    public double errorBudgetTotalPct() {
        return 100.0 - targetPct;
    }

    public double errorBudgetConsumedPct() {
        if (errorBudgetTotalPct() == 0) return 100.0;
        double deficit = Math.max(0, targetPct - currentSliPct());
        return Math.min(100.0, deficit / errorBudgetTotalPct() * 100.0);
    }

    public double errorBudgetRemainingPct() {
        return Math.max(0.0, 100.0 - errorBudgetConsumedPct());
    }

    /**
     * Burn rate > 1 means budget is consumed faster than it replenishes.
     * Burn rate > 14.4 = critical (budget exhausted in < 1 hour).
     */
    public double burnRate() {
        int total = currentWindowEvents();
        if (total == 0) return 0.0;
        double actualErrorRate = (double) badTicks.size() / total;
        double allowedErrorRate = errorBudgetTotalPct() / 100.0;
        if (allowedErrorRate == 0) return Double.MAX_VALUE;
        return actualErrorRate / allowedErrorRate;
    }

    public SloStatus status() {
        double br = burnRate();
        double remaining = errorBudgetRemainingPct();
        if (br >= 14.4)    return SloStatus.CRITICAL;
        if (br >= 6.0)     return SloStatus.WARNING;
        if (remaining < 10) return SloStatus.AT_RISK;
        return SloStatus.OK;
    }

    public SloReport toReport() {
        return new SloReport(
            name,
            targetPct,
            round(currentSliPct(), 3),
            round(errorBudgetRemainingPct(), 2),
            round(burnRate(), 3),
            status().name(),
            currentWindowEvents(),
            goodTicks.size(),
            badTicks.size()
        );
    }

    private static double round(double val, int places) {
        double factor = Math.pow(10, places);
        return Math.round(val * factor) / factor;
    }

    // ─── Nested types ─────────────────────────────────────────────────────────
    public enum SloStatus { OK, WARNING, AT_RISK, CRITICAL }

    public record SloReport(
        String name,
        double targetPct,
        double currentSliPct,
        double errorBudgetRemainingPct,
        double burnRate,
        String status,
        int windowEvents,
        int goodEvents,
        int badEvents
    ) {}
}
