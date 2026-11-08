/*
 * AI Assistance:
 * This test suite was developed and refined with advice, guidance,
 * and code assistance from OpenAI ChatGPT.
 * Test implementations were reviewed and validated as part of
 * the development process.
 */
package edu.adelaide.kali.service;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MockSubtitleServiceTest {
    @Test
    void providesCaptionAfterTypicalAsyncExtractionDelay() {
        var result = new MockSubtitleService().transcribe(
                new MockMultipartFile("audio", "test.wav", "audio/wav", new byte[] {1, 2}));
        assertTrue(result.segments().stream().anyMatch(segment -> segment.start() <= 35 && segment.end() > 35));
        assertTrue(result.segments().size() > 3);
        assertFalse(result.words().isEmpty());
    }

    @Test
    void rejectsMissingAudio() {
        assertThrows(IllegalArgumentException.class,
                () -> new MockSubtitleService().transcribe(
                        new MockMultipartFile("audio", new byte[0])));
    }
}
