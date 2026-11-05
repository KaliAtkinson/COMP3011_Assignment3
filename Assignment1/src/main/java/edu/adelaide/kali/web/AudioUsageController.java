package edu.adelaide.kali.web;

import edu.adelaide.kali.config.AudioUsageTracker;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Separate duration-based usage endpoint required by Assignment 3. */
@RestController
public class AudioUsageController {
    private final AudioUsageTracker tracker;

    public AudioUsageController(AudioUsageTracker tracker) {
        this.tracker = tracker;
    }

    @GetMapping("/api/v1/global/usage")
    public UsageResponse getUsage() {
        return new UsageResponse(tracker.getSeconds());
    }

    public record UsageResponse(double usage) {}
}
