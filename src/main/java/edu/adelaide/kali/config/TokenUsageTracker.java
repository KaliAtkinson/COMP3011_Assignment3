package edu.adelaide.kali.config;

import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class TokenUsageTracker {

    private final AtomicLong inputTokens = new AtomicLong(0);
    private final AtomicLong outputTokens = new AtomicLong(0);

    public void addInputTokens(long amount) {
        inputTokens.addAndGet(amount);
    }

    public void addOutputTokens(long amount) {
        outputTokens.addAndGet(amount);
    }

    public long getInputTokens() {
        return inputTokens.get();
    }

    public long getOutputTokens() {
        return outputTokens.get();
    }
}