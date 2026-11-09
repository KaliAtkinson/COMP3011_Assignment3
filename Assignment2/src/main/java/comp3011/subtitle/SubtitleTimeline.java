package comp3011.subtitle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable, seek-safe schedule of subtitle phrases for one media file.
 *
 * <p>Times are seconds from the start of the original media. The timeline is
 * built once when transcription completes, then queried against the current
 * playback position. It stores no playback cursor, so paused, backwards and
 * forwards seeks need no special synchronisation and may be queried safely
 * from different threads.
 *
 * <p>Whisper word timestamps are used to split long segments into readable
 * phrases at pauses and punctuation, with a segment-level fallback when word
 * data is absent. Phrases are displayed for at least one second where there
 * is room, and at most seven seconds. Incoming phrases take priority and clip
 * previous ones to prevent overlap.
 *
 * <p>AI Assistance: Developed with advice and code assistance from OpenAI ChatGPT.
 */
public final class SubtitleTimeline {
    private static final double MIN_DISPLAY_SECONDS = 1.0;
    private static final double MAX_DISPLAY_SECONDS = 7.0;

    private final List<Cue> cues;

    private SubtitleTimeline(List<Cue> cues) {
        this.cues = List.copyOf(cues);
    }

    /** Returns a reusable empty schedule while captions are unavailable. */
    public static SubtitleTimeline empty() {
        return new SubtitleTimeline(List.of());
    }

    /**
     * Converts the server's phrase timestamps to a non-overlapping timeline.
     * An empty or silence-only transcription produces an empty timeline.
     */
    public static SubtitleTimeline fromTranscript(SubtitleTranscript transcript) {
        Objects.requireNonNull(transcript, "transcript");

        // Prefer Whisper's per-word times when present. This avoids showing
        // a whole long segment before its later words are spoken, and prevents
        // the two-line formatter from silently losing the rest of the dialogue.
        // A segment without usable word timestamps keeps its old fallback.
        List<Cue> phrases = SubtitleWordCues.build(transcript);
        List<Cue> held = new ArrayList<>(phrases.size());
        for (Cue phrase : phrases) {
            double end = Math.min(Math.max(phrase.end(), phrase.start() + MIN_DISPLAY_SECONDS),
                    phrase.start() + MAX_DISPLAY_SECONDS);
            held.add(new Cue(phrase.start(), end, phrase.text()));
        }
        phrases = held;

        // Timings from a cloud service need not arrive in playback order.
        // List.sort is stable: if two phrases share a start, the later server
        // entry wins, rather than briefly displaying two captions at once.
        phrases.sort(Comparator.comparingDouble(Cue::start));
        List<Cue> distinct = new ArrayList<>(phrases.size());
        for (Cue phrase : phrases) {
            if (!distinct.isEmpty() && distinct.getLast().start() == phrase.start()) {
                distinct.set(distinct.size() - 1, phrase);
            } else {
                distinct.add(phrase);
            }
        }

        List<Cue> scheduled = new ArrayList<>(distinct.size());
        for (int i = 0; i < distinct.size(); i++) {
            Cue current = distinct.get(i);
            double end = current.end();
            if (i + 1 < distinct.size()) {
                end = Math.min(end, distinct.get(i + 1).start());
            }
            // The next cue always starts strictly later, so the interval
            // remains positive even if we have to shorten the one-second hold.
            scheduled.add(new Cue(current.start(), end, current.text()));
        }
        return new SubtitleTimeline(scheduled);
    }

    /**
     * Returns the phrase at a playback timestamp, or empty during a gap.
     * The start is inclusive and the end is exclusive. Binary search makes
     * this O(log n) even for repeated frame-by-frame lookups or arbitrary seeks.
     */
    public Optional<Cue> atSeconds(double playbackSeconds) {
        if (!Double.isFinite(playbackSeconds) || playbackSeconds < 0 || cues.isEmpty()) {
            return Optional.empty();
        }
        int low = 0;
        int high = cues.size();
        while (low < high) {
            int middle = low + (high - low) / 2;
            if (cues.get(middle).start() <= playbackSeconds) {
                low = middle + 1;
            } else {
                high = middle;
            }
        }
        int candidate = low - 1;
        if (candidate < 0) {
            return Optional.empty();
        }
        Cue cue = cues.get(candidate);
        return playbackSeconds < cue.end() ? Optional.of(cue) : Optional.empty();
    }

    /** All scheduled captions in start-time order; the returned list is immutable. */
    public List<Cue> cues() {
        return cues;
    }

    /** One non-overlapping, half-open subtitle interval [start, end). */
    public record Cue(double start, double end, String text) {
        public Cue {
            if (!Double.isFinite(start) || start < 0 || !Double.isFinite(end) || end <= start) {
                throw new IllegalArgumentException("Cue requires a positive finite time interval");
            }
            Objects.requireNonNull(text, "text");
        }
    }
}
