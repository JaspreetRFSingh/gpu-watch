package com.gpuguard.remediation;

import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe Circuit Breaker.
 * Prevents cascading remediation retry storms.
 * Equivalent to the Python CircuitBreaker class.
 */
@Slf4j
public class CircuitBreaker {

    private final int maxAttempts;
    private final long windowMs;
    private final long cooldownMs;

    private final Map<String, Deque<Long>> attempts = new ConcurrentHashMap<>();
    private final Map<String, Long> openUntil = new ConcurrentHashMap<>();

    public CircuitBreaker(int maxAttempts, long windowMs, long cooldownMs) {
        this.maxAttempts = maxAttempts;
        this.windowMs = windowMs;
        this.cooldownMs = cooldownMs;
    }

    public boolean isOpen(String key) {
        Long until = openUntil.get(key);
        return until != null && System.currentTimeMillis() < until;
    }

    /**
     * @return true if attempt is allowed, false if circuit is open
     */
    public boolean tryAcquire(String key) {
        if (isOpen(key)) {
            log.warn("CircuitBreaker OPEN for {}. Skipping remediation.", key);
            return false;
        }

        long now = System.currentTimeMillis();
        attempts.computeIfAbsent(key, k -> new ArrayDeque<>());
        Deque<Long> history = attempts.get(key);

        // Evict stale attempts
        history.removeIf(t -> now - t > windowMs);
        history.addLast(now);

        if (history.size() >= maxAttempts) {
            openUntil.put(key, now + cooldownMs);
            log.error("CircuitBreaker TRIPPED for {} after {} attempts. Cooling down for {}s.",
                key, maxAttempts, cooldownMs / 1000);
            return false;
        }
        return true;
    }

    public void reset(String key) {
        attempts.remove(key);
        openUntil.remove(key);
    }
}
