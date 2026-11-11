package comp3011.subtitle;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Optional comparison of Whisper's raw segments with captions actually displayed.
 *
 * <p>Neither transcriptions nor timing are logged or saved by default. When
 * COMP3011_EXPORT_SUBTITLES=1, the successful response is exported to two
 * temporary SRT files on a background thread. This helps distinguish Whisper
 * recognition / timing errors from our word-based caption splitting. Export
 * failures never interrupt playback or cause another paid request.
 *
 * <p>AI Assistance: Developed with advice and code assistance from OpenAI ChatGPT.
 */
public final class SubtitleSrtExporter {
    private SubtitleSrtExporter() { }

    /** Paths for original Whisper segments and the final caption schedule. */
    public record Exported(Path segments, Path captions) { }

    /** Opt in explicitly; subtitle text may contain private spoken material. */
    public static boolean isEnabled() {
        return "1".equals(System.getenv("COMP3011_EXPORT_SUBTITLES"))
                || Boolean.getBoolean("comp3011.exportSubtitles");
    }

    /**
     * Enqueues file I/O independently of the HTTP callback and JavaFX thread.
     * A diagnostic export must never affect playback or the subtitle request.
     */
    public static void exportIfRequested(SubtitleTranscript transcript, SubtitleTimeline timeline) {
        if (!isEnabled()) {
            return;
        }
        CompletableFuture.runAsync(() -> {
            try {
                Exported files = exportToDirectory(
                        Files.createTempDirectory("comp3011-subtitle-diagnostics-"),
                        transcript, timeline);
                System.err.println("[subtitles] Whisper segment SRT: " + files.segments());
                System.err.println("[subtitles] Displayed caption SRT: " + files.captions());
            } catch (IOException | SecurityException failure) {
                System.err.println("[subtitles] Optional SRT export failed (" 
                        + failure.getClass().getSimpleName() + ")");
            }
        });
    }

    /**
     * Writes two independent SRT files for inspection. Package-private so
     * tests can provide a temporary directory without changing process env.
     */
    static Exported exportToDirectory(Path directory, SubtitleTranscript transcript,
                                      SubtitleTimeline timeline) throws IOException {
        Path raw = directory.resolve("whisper-segments.srt");
        Path displayed = directory.resolve("displayed-captions.srt");
        Files.writeString(raw, rawSegmentsSrt(transcript), StandardCharsets.UTF_8);
        try {
            Files.writeString(displayed, displayedCaptionsSrt(timeline), StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException failure) {
            Files.deleteIfExists(raw);
            throw failure;
        }
        return new Exported(raw, displayed);
    }

    /** Retains Whisper's segment wording and timestamps; normalises whitespace for SRT. */
    static String rawSegmentsSrt(SubtitleTranscript transcript) {
        StringBuilder out = new StringBuilder();
        int index = 0;
        for (SubtitleTranscript.Segment segment : transcript.segments()) {
            if (segment.noSpeechProb() > 0.6 && segment.avgLogProb() < -1.0) {
                continue;
            }
            String text = normalise(segment.text());
            if (text.isEmpty() || segment.end() <= segment.start()) {
                continue;
            }
            append(out, ++index, segment.start(), segment.end(), text);
        }
        return out.toString();
    }

    /** Exports the precise phrases and times used by the live player. */
    static String displayedCaptionsSrt(SubtitleTimeline timeline) {
        StringBuilder out = new StringBuilder();
        int index = 0;
        for (SubtitleTimeline.Cue cue : timeline.cues()) {
            List<String> lines = SubtitleCaptionFormatter.lines(cue.text());
            if (lines.isEmpty()) {
                continue;
            }
            append(out, ++index, cue.start(), cue.end(), String.join("\n", lines));
        }
        return out.toString();
    }

    private static String normalise(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static void append(StringBuilder out, int index, double start,
                               double end, String text) {
        out.append(index).append('\n')
                .append(timestamp(start)).append(" --> ").append(timestamp(end)).append('\n')
                .append(text).append("\n\n");
    }

    private static String timestamp(double seconds) {
        long milliseconds = Math.round(seconds * 1000);
        long hours = milliseconds / 3_600_000;
        long minutes = milliseconds / 60_000 % 60;
        long wholeSeconds = milliseconds / 1_000 % 60;
        long remainder = milliseconds % 1_000;
        return String.format(Locale.ROOT, "%02d:%02d:%02d,%03d",
                hours, minutes, wholeSeconds, remainder);
    }
}
