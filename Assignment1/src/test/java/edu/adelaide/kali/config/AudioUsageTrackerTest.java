package edu.adelaide.kali.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/*
 * AI Assistance:
 * This test suite was developed and refined with advice, guidance,
 * and code assistance from OpenAI ChatGPT.
 * Test implementations were reviewed and validated as part of
 * the development process.
 */
class AudioUsageTrackerTest {
    @Test
    void addsDurationsWithoutLosingFractions() {
        var tracker = new AudioUsageTracker();
        tracker.addSeconds(1.25);
        tracker.addSeconds(0.125);
        assertEquals(1.375, tracker.getSeconds(), 0.000001);
    }

    @Test
    void rejectsInvalidDurations() {
        var tracker = new AudioUsageTracker();
        assertThrows(IllegalArgumentException.class, () -> tracker.addSeconds(-1));
        assertThrows(IllegalArgumentException.class, () -> tracker.addSeconds(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> tracker.addSeconds(Double.POSITIVE_INFINITY));
    }

    @Test
    void countsConcurrentRequestsCorrectly() throws InterruptedException {
        var tracker = new AudioUsageTracker();
        int requests = 100;
        CountDownLatch finished = new CountDownLatch(requests);
        try (var pool = Executors.newFixedThreadPool(8)) {
            for (int i = 0; i < requests; i++) {
                pool.submit(() -> {
                    try {
                        tracker.addSeconds(0.125);
                    } finally {
                        finished.countDown();
                    }
                });
            }
            assertTrue(finished.await(5, TimeUnit.SECONDS));
        }
        assertEquals(12.5, tracker.getSeconds(), 0.000001);
    }
}
