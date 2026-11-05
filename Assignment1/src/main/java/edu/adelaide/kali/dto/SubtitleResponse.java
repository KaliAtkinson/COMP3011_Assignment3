package edu.adelaide.kali.dto;

import java.util.List;

/**
 * Timed data returned by the Assignment 3 subtitle endpoint.
 * All times are seconds relative to the beginning of the uploaded audio.
 *
 * AI Assistance: Developed with guidance and code assistance from OpenAI ChatGPT.
 */
public record SubtitleResponse(
        String text,
        double duration,
        List<Segment> segments,
        List<Word> words) {

    public SubtitleResponse {
        if (text == null) {
            text = "";
        }
        if (!Double.isFinite(duration) || duration < 0) {
            throw new IllegalArgumentException("duration must be a non-negative finite value");
        }
        segments = List.copyOf(segments);
        words = List.copyOf(words);
    }

    /** A recognised phrase and Whisper's confidence indicators. */
    public record Segment(double start, double end, String text,
                          double noSpeechProb, double avgLogProb) {}

    /** One recognised word and its timing. */
    public record Word(double start, double end, String word) {}
}
