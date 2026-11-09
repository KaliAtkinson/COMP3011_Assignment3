/*
 * AI Assistance:
 * This test suite was developed and refined with advice, guidance,
 * and code assistance from OpenAI ChatGPT.
 * Test implementations were reviewed and validated as part of
 * the development process.
 */
package comp3011.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubtitleSrtExporterTest {
    @TempDir Path tempDirectory;

    @Test
    void preservesWhisperSegmentsAndShowsTheActualSplitCaptionTimes() {
        SubtitleTranscript transcript = new SubtitleTranscript("Hello there. More words", 8.0,
                List.of(segment(0.0, 8.0, "Hello there. More words")),
                List.of(word(1.5, 1.7, "Hello"), word(1.8, 2.1, "there."),
                        word(4.0, 4.3, "More"), word(4.4, 4.7, "words")));
        SubtitleTimeline timeline = SubtitleTimeline.fromTranscript(transcript);

        String raw = SubtitleSrtExporter.rawSegmentsSrt(transcript);
        String captions = SubtitleSrtExporter.displayedCaptionsSrt(timeline);

        assertTrue(raw.contains("00:00:00,000 --> 00:00:08,000"));
        assertTrue(raw.contains("Hello there. More words"));
        assertTrue(captions.contains("00:00:01,500 --> 00:00:02,500"));
        assertTrue(captions.contains("00:00:04,000 --> 00:00:05,000"));
        assertTrue(captions.contains("Hello there."));
        assertTrue(captions.contains("More words"));
    }

    @Test
    void exportsUtf8SrtFilesAndDoesNotIncludeSilence() throws Exception {
        SubtitleTranscript transcript = new SubtitleTranscript("Café", 6.0,
                List.of(new SubtitleTranscript.Segment(0.0, 2.0, "hallucinated", .9, -1.5),
                        segment(3.25, 4.75, " Café   bientôt! ")),
                List.of());
        SubtitleTimeline timeline = SubtitleTimeline.fromTranscript(transcript);
        var files = SubtitleSrtExporter.exportToDirectory(tempDirectory, transcript, timeline);
        String raw = Files.readString(files.segments());
        String captions = Files.readString(files.captions());
        assertEquals("1\n00:00:03,250 --> 00:00:04,750\nCafé bientôt!\n\n", raw);
        assertTrue(captions.contains("Café bientôt!"));
        assertFalse(raw.contains("hallucinated"));
        assertEquals(tempDirectory, files.segments().getParent());
        assertEquals(tempDirectory, files.captions().getParent());
    }

    @Test
    void timestampsUseSrtHoursMinutesAndMillis() {
        var transcript = new SubtitleTranscript("test", 3663,
                List.of(segment(3661.234, 3662.987, "at the hour")), List.of());
        assertTrue(SubtitleSrtExporter.rawSegmentsSrt(transcript)
                .contains("01:01:01,234 --> 01:01:02,987"));
    }

    @Test
    void emptyTimelinesYieldEmptySrtFiles() {
        var transcript = new SubtitleTranscript("", 0, List.of(), List.of());
        assertEquals("", SubtitleSrtExporter.rawSegmentsSrt(transcript));
        assertEquals("", SubtitleSrtExporter.displayedCaptionsSrt(SubtitleTimeline.empty()));
    }

    @Test
    void exportedCaptionLinesRespectLayoutLimits() {
        String text = "One line of text that should be wrapped carefully, with readable words in the second line";
        SubtitleTimeline timeline = SubtitleTimeline.fromTranscript(new SubtitleTranscript("", 10,
                List.of(segment(1.0, 3.0, text)), List.of()));
        String exported = SubtitleSrtExporter.displayedCaptionsSrt(timeline);
        String[] lines = exported.split("\\R");
        for (int i = 2; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                assertTrue(lines[i].length() <= 42);
            }
        }
    }

    private static SubtitleTranscript.Segment segment(double start, double end, String text) {
        return new SubtitleTranscript.Segment(start, end, text, 0, -0.1);
    }

    private static SubtitleTranscript.Word word(double start, double end, String text) {
        return new SubtitleTranscript.Word(start, end, text);
    }
}
