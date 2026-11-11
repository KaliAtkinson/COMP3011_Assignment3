package comp3011.subtitle;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.Objects;

/**
 * Immutable response from Assignment 1's /api/v1/subtitles endpoint.
 * Times are seconds from the start of the extracted media audio.
 *
 * AI Assistance: Developed with advice and code assistance from OpenAI ChatGPT.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record SubtitleTranscript(
        String text,
        double duration,
        List<Segment> segments,
        List<Word> words) {

    public SubtitleTranscript {
        text = text == null ? "" : text;
        validateTime(duration, "duration");
        segments = List.copyOf(Objects.requireNonNull(segments, "segments"));
        words = List.copyOf(Objects.requireNonNull(words, "words"));
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Segment(double start, double end, String text,
                          double noSpeechProb, double avgLogProb) {
        public Segment {
            validateRange(start, end);
            text = text == null ? "" : text;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Word(double start, double end, String word) {
        public Word {
            validateRange(start, end);
            word = word == null ? "" : word;
        }
    }

    private static void validateTime(double time, String name) {
        if (!Double.isFinite(time) || time < 0) {
            throw new IllegalArgumentException(name + " must be finite and non-negative");
        }
    }

    private static void validateRange(double start, double end) {
        validateTime(start, "start");
        validateTime(end, "end");
        if (end < start) {
            throw new IllegalArgumentException("end cannot be before start");
        }
    }
}
