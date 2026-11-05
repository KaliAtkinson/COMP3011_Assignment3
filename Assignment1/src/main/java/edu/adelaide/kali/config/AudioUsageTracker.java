package edu.adelaide.kali.config;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks cumulative audio duration sent to the cloud transcription service.
 * Fixed-point microseconds avoid lost updates from concurrent uploads.
 * The total resets when the server restarts (no persistent storage yet).
 *
 * AI Assistance: Developed with guidance and code assistance from OpenAI ChatGPT.
 */
@Component
public class AudioUsageTracker {
    private static final long MICROS_PER_SECOND = 1_000_000L;
    private final AtomicLong microseconds = new AtomicLong();

    public void addSeconds(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0
                || seconds > Long.MAX_VALUE / (double) MICROS_PER_SECOND) {
            throw new IllegalArgumentException("Duration must be a finite, non-negative number");
        }
        microseconds.addAndGet(Math.round(seconds * MICROS_PER_SECOND));
    }

    public double getSeconds() {
        return microseconds.get() / (double) MICROS_PER_SECOND;
    }
}
