package comp3011.subtitle;

import java.util.Objects;
import java.util.Optional;

/**
 * Determines when the visible caption needs to change, independently of video decoding.
 *
 * <p>The JavaFX thread owns this small state object. Caption lookup uses the
 * current displayed media timestamp, not a decoder worker's prefetched frame,
 * so a late transcription, pause, seek, or subtitle toggle is reflected without
 * restarting the decoder. Empty text means that the overlay should be hidden.
 *
 * <p>AI Assistance: Developed with advice and code assistance from OpenAI ChatGPT.
 */
public final class SubtitleDisplayState {
    private String displayed = "";

    /**
     * Returns the new caption only if its text differs from the previous display.
     * The Optional may contain an empty string, which means hide the overlay.
     */
    public Optional<String> update(boolean enabled, SubtitleTimeline timeline, long timestampUs) {
        Objects.requireNonNull(timeline, "timeline");
        String next = enabled && timestampUs >= 0
                ? timeline.atSeconds(timestampUs / 1_000_000.0)
                    .map(SubtitleTimeline.Cue::text).orElse("")
                : "";
        return changeTo(next);
    }

    /** Clears any caption when playback is stopped or a seek invalidates the old frame. */
    public Optional<String> clear() {
        return changeTo("");
    }

    private Optional<String> changeTo(String next) {
        if (displayed.equals(next)) {
            return Optional.empty();
        }
        displayed = next;
        return Optional.of(next);
    }
}
