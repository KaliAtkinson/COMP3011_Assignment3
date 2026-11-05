package edu.adelaide.kali.subtitle;

import edu.adelaide.kali.dto.SubtitleResponse;
import edu.adelaide.kali.dto.SubtitleResponse.Segment;
import edu.adelaide.kali.dto.SubtitleResponse.Word;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Converts a whisper-1 verbose_json response into the stable API format used
 * by the video player. No HTTP calls, state, or mutable shared collections.
 *
 * AI Assistance: Developed with guidance and code assistance from OpenAI ChatGPT.
 */
public final class WhisperSubtitleMapper {
    private static final double NO_SPEECH_THRESHOLD = 0.6;
    private static final double LOW_LOG_PROB_THRESHOLD = -1.0;

    private WhisperSubtitleMapper() {}

    public static SubtitleResponse fromVerboseJson(Map<?, ?> response) {
        if (response == null) {
            throw new IllegalArgumentException("response must not be null");
        }

        List<Segment> segments = new ArrayList<>();
        List<TimeRange> excluded = new ArrayList<>();
        double latestEnd = 0.0;

        for (Map<?, ?> entry : mapsIn(response.get("segments"))) {
            Double start = nonNegativeNumber(entry.get("start"));
            Double end = nonNegativeNumber(entry.get("end"));
            if (start == null || end == null || end < start) {
                continue;
            }
            latestEnd = Math.max(latestEnd, end);
            double noSpeechProb = numberOrDefault(entry.get("no_speech_prob"), 0.0);
            double avgLogProb = numberOrDefault(entry.get("avg_logprob"), 0.0);
            if (noSpeechProb > NO_SPEECH_THRESHOLD && avgLogProb < LOW_LOG_PROB_THRESHOLD) {
                excluded.add(new TimeRange(start, end));
                continue;
            }
            segments.add(new Segment(start, end, stringOrEmpty(entry.get("text")),
                    noSpeechProb, avgLogProb));
        }

        List<Word> words = new ArrayList<>();
        for (Map<?, ?> entry : mapsIn(response.get("words"))) {
            Double start = nonNegativeNumber(entry.get("start"));
            Double end = nonNegativeNumber(entry.get("end"));
            if (start == null || end == null || end < start) {
                continue;
            }
            latestEnd = Math.max(latestEnd, end);
            double middle = start + (end - start) / 2.0;
            if (excluded.stream().anyMatch(range -> range.contains(middle))) {
                continue;
            }
            words.add(new Word(start, end, stringOrEmpty(entry.get("word"))));
        }

        Double reportedDuration = nonNegativeNumber(response.get("duration"));
        double duration = reportedDuration == null ? latestEnd : reportedDuration;
        return new SubtitleResponse(stringOrEmpty(response.get("text")), duration, segments, words);
    }

    private static String stringOrEmpty(Object value) {
        return value instanceof String text ? text : "";
    }

    private static Double nonNegativeNumber(Object value) {
        if (!(value instanceof Number number)) {
            return null;
        }
        double result = number.doubleValue();
        return Double.isFinite(result) && result >= 0 ? result : null;
    }

    private static double numberOrDefault(Object value, double fallback) {
        if (value instanceof Number number && Double.isFinite(number.doubleValue())) {
            return number.doubleValue();
        }
        return fallback;
    }

    private static List<Map<?, ?>> mapsIn(Object value) {
        if (!(value instanceof List<?> entries)) {
            return List.of();
        }
        List<Map<?, ?>> maps = new ArrayList<>();
        for (Object entry : entries) {
            if (entry instanceof Map<?, ?> map) {
                maps.add(map);
            }
        }
        return maps;
    }

    private record TimeRange(double start, double end) {
        boolean contains(double timestamp) {
            return timestamp >= start && timestamp < end;
        }
    }
}
