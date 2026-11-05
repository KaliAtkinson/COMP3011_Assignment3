package edu.adelaide.kali.service;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/*
 * AI Assistance:
 * This test suite was developed and refined with advice, guidance,
 * and code assistance from OpenAI ChatGPT.
 * Test implementations were reviewed and validated as part of
 * the development process.
 */
class WhisperUsageTest {
    @Test
    void prefersServiceReportedUsageSeconds() {
        assertEquals(4.0, WhisperSubtitleService.reportedUsageSeconds(
                Map.of("usage", Map.of("type", "duration", "seconds", 4)), 3.32));
    }

    @Test
    void fallsBackToAudioDurationWhenUsageAbsent() {
        assertEquals(3.32, WhisperSubtitleService.reportedUsageSeconds(
                Map.of("duration", 3.32), 3.32));
    }
}
