package edu.adelaide.kali.web;

import edu.adelaide.kali.config.TokenUsageTracker;
import edu.adelaide.kali.dto.GlobalStatsResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StatsController {

    private final TokenUsageTracker tokenUsageTracker;

    public StatsController(TokenUsageTracker tokenUsageTracker) {
        this.tokenUsageTracker = tokenUsageTracker;
    }

    @GetMapping("/api/v1/global/stats")
    public GlobalStatsResponse getGlobalStats() {
        return new GlobalStatsResponse(
            tokenUsageTracker.getInputTokens(),
            tokenUsageTracker.getOutputTokens()
        );
    }
}