package edu.adelaide.kali.subtitle;

import edu.adelaide.kali.dto.SubtitleResponse;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/*
 * AI Assistance:
 * This test suite was developed and refined with advice, guidance,
 * and code assistance from OpenAI ChatGPT.
 * Test implementations were reviewed and validated as part of
 * the development process.
 */
class WhisperSubtitleMapperTest {
    @Test
    void mapsWordAndSegmentTimingsFromVerboseJson() {
        Map<String, Object> response = Map.of(
                "text", "Hello world.", "duration", 3.5,
                "segments", List.of(Map.of("start", 0.0, "end", 2.0,
                        "text", "Hello world.", "no_speech_prob", 0.03,
                        "avg_logprob", -0.2)),
                "words", List.of(Map.of("start", 0.0, "end", 0.5, "word", "Hello"),
                        Map.of("start", 0.5, "end", 2.0, "word", "world.")));

        SubtitleResponse result = WhisperSubtitleMapper.fromVerboseJson(response);

        assertEquals("Hello world.", result.text());
        assertEquals(3.5, result.duration());
        assertEquals(1, result.segments().size());
        assertEquals(2.0, result.segments().getFirst().end());
        assertEquals("world.", result.words().get(1).word());
    }

    @Test
    void excludesOnlySegmentsMeetingBothNoSpeechConditionsAndTheirWords() {
        Map<String, Object> silent = Map.of("start", 0.0, "end", 2.0,
                "text", "noise", "no_speech_prob", 0.9, "avg_logprob", -1.5);
        Map<String, Object> uncertainButSpoken = Map.of("start", 2.0, "end", 4.0,
                "text", "Hello", "no_speech_prob", 0.9, "avg_logprob", -0.5);
        Map<String, Object> lowProbabilityButSpoken = Map.of("start", 4.0, "end", 6.0,
                "text", "World", "no_speech_prob", 0.2, "avg_logprob", -2.0);
        Map<String, Object> response = Map.of(
                "segments", List.of(silent, uncertainButSpoken, lowProbabilityButSpoken),
                "words", List.of(Map.of("start", 0.5, "end", 1.0, "word", "noise"),
                        Map.of("start", 2.5, "end", 3.0, "word", "Hello"),
                        Map.of("start", 4.5, "end", 5.0, "word", "World")));

        SubtitleResponse result = WhisperSubtitleMapper.fromVerboseJson(response);

        assertEquals(List.of("Hello", "World"),
                result.segments().stream().map(SubtitleResponse.Segment::text).toList());
        assertEquals(List.of("Hello", "World"),
                result.words().stream().map(SubtitleResponse.Word::word).toList());
        assertEquals(6.0, result.duration());
    }

    @Test
    void handlesMissingTimestampArraysWithoutInventingCaptions() {
        SubtitleResponse result = WhisperSubtitleMapper.fromVerboseJson(Map.of("text", "Hello"));
        assertEquals("Hello", result.text());
        assertTrue(result.segments().isEmpty());
        assertTrue(result.words().isEmpty());
        assertEquals(0.0, result.duration());
    }

    @Test
    void skipsInvalidTimingsAndPreservesImmutableResults() {
        Map<String, Object> response = Map.of(
                "segments", List.of(Map.of("start", 4.0, "end", 2.0, "text", "invalid"),
                        Map.of("start", 2.0, "end", 3.0, "text", "valid")),
                "words", List.of(Map.of("start", -1.0, "end", 0.0, "word", "invalid")));
        SubtitleResponse result = WhisperSubtitleMapper.fromVerboseJson(response);
        assertEquals(1, result.segments().size());
        assertEquals("valid", result.segments().getFirst().text());
        assertThrows(UnsupportedOperationException.class, () -> result.segments().clear());
    }
}
