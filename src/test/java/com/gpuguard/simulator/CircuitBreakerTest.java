package com.gpuguard.simulator;

import com.gpuguard.remediation.CircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

@DisplayName("CircuitBreaker")
class CircuitBreakerTest {

    private CircuitBreaker cb;

    @BeforeEach
    void setUp() {
        cb = new CircuitBreaker(3, 60_000L, 120_000L);
    }

    @Test
    @DisplayName("allows attempts below the threshold")
    void allowsBelowThreshold() {
        assertThat(cb.tryAcquire("job-1")).isTrue();
        assertThat(cb.tryAcquire("job-1")).isTrue();
        assertThat(cb.isOpen("job-1")).isFalse();
    }

    @Test
    @DisplayName("opens the circuit after maxAttempts")
    void opensAfterMaxAttempts() {
        cb.tryAcquire("job-1");
        cb.tryAcquire("job-1");
        cb.tryAcquire("job-1"); // trips the breaker

        assertThat(cb.isOpen("job-1")).isTrue();
        assertThat(cb.tryAcquire("job-1")).isFalse();
    }

    @Test
    @DisplayName("different keys are tracked independently")
    void differentKeysAreIndependent() {
        cb.tryAcquire("job-1");
        cb.tryAcquire("job-1");
        cb.tryAcquire("job-1"); // trips job-1

        assertThat(cb.isOpen("job-1")).isTrue();
        assertThat(cb.isOpen("job-2")).isFalse();
        assertThat(cb.tryAcquire("job-2")).isTrue();
    }

    @Test
    @DisplayName("reset clears the open circuit")
    void resetClearsCircuit() {
        cb.tryAcquire("job-1");
        cb.tryAcquire("job-1");
        cb.tryAcquire("job-1");
        assertThat(cb.isOpen("job-1")).isTrue();

        cb.reset("job-1");

        assertThat(cb.isOpen("job-1")).isFalse();
        assertThat(cb.tryAcquire("job-1")).isTrue();
    }

    @Test
    @DisplayName("isOpen returns false for keys that were never used")
    void isOpenFalseForUnknownKey() {
        assertThat(cb.isOpen("never-seen")).isFalse();
    }
}
