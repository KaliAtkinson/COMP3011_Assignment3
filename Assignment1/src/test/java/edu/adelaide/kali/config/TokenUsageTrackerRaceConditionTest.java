package edu.adelaide.kali.config;

import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenUsageTrackerRaceConditionTest {

    @Test
    void concurrentIncrementsAreNotLost() throws InterruptedException {
        TokenUsageTracker tracker = new TokenUsageTracker();

        int threadCount = 500;
        long incrementPerThread = 10;
        long expectedTotal = threadCount * incrementPerThread;

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            executor.submit(() -> {
                try {
                    for (int j = 0; j < incrementPerThread; j++) {
                        tracker.addInputTokens(1);
                        tracker.addOutputTokens(1);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completed = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(completed, "Not all threads completed in time");
        assertEquals(expectedTotal, tracker.getInputTokens(),
            "Input token count is wrong — increments were lost under concurrent access");
        assertEquals(expectedTotal, tracker.getOutputTokens(),
            "Output token count is wrong — increments were lost under concurrent access");
    }
}