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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class SubtitleWordCuesTest {
    @Test
    void splitsLongSpeechIntoCompleteTwoLineCaptionsWithoutDroppingWords() {
        String[] spoken = ("When the spaceship came into view everyone stopped and watched "
                + "because nobody had ever seen anything like it before and the captain "
                + "said we should turn around and head straight home").split(" ");
        List<SubtitleTranscript.Word> words = new ArrayList<>();
        for (int index = 0; index < spoken.length; index++) {
            words.add(new SubtitleTranscript.Word(index * 0.42, index * 0.42 + 0.30, spoken[index]));
        }
        SubtitleTimeline timeline = from(
                List.of(segment(0.0, spoken.length * 0.42 + 0.5, String.join(" ", spoken))), words);

        assertTrue(timeline.cues().size() > 2, "A long segment should yield several timed phrases");
        String recovered = timeline.cues().stream().map(SubtitleTimeline.Cue::text)
                .collect(Collectors.joining(" "));
        assertEquals(String.join(" ", spoken), recovered, "No dialogue may be truncated");
        for (SubtitleTimeline.Cue cue : timeline.cues()) {
            List<String> lines = SubtitleCaptionFormatter.lines(cue.text());
            assertTrue(lines.size() <= 2);
            assertTrue(lines.stream().allMatch(line -> line.length() <= 42), cue.text());
            assertFalse(cue.text().endsWith("…"), "No phrase should rely on text truncation");
            assertTrue(cue.end() - cue.start() <= 7.0);
        }
        assertEquals(0.0, timeline.cues().getFirst().start(), 0.0001);
    }

    @Test
    void startsCaptionAtFirstSpokenWordNotEarlySegmentTimestamp() {
        SubtitleTimeline timeline = from(List.of(segment(0, 5, "Wait for it")), List.of(
                word(1.5, 1.8, "Wait"), word(1.9, 2.2, "for"), word(2.3, 2.6, "it")));

        assertTrue(timeline.atSeconds(0.8).isEmpty());
        assertEquals("Wait for it", timeline.atSeconds(1.5).orElseThrow().text());
        assertEquals(1.5, timeline.cues().getFirst().start());
    }

    @Test
    void splitsAtNaturalPauseAndSentenceBoundary() {
        SubtitleTimeline timeline = from(List.of(segment(0, 10, "Hello there. This is new.")), List.of(
                word(0.2, 0.7, "Hello"), word(0.8, 1.1, "there."),
                word(1.2, 1.45, "This"), word(1.5, 1.8, "is"), word(1.9, 2.4, "new.")));
        assertEquals(2, timeline.cues().size());
        assertEquals("Hello there.", timeline.cues().get(0).text());
        assertEquals("This is new.", timeline.cues().get(1).text());
        assertEquals(1.2, timeline.cues().get(1).start());

        SubtitleTimeline paused = from(List.of(segment(0, 8, "Some words follow later")), List.of(
                word(0.0, 0.5, "Some"), word(0.5, 0.9, "words"),
                word(2.1, 2.5, "follow"), word(2.5, 3.0, "later")));
        assertEquals(2, paused.cues().size());
        assertEquals("Some words", paused.cues().getFirst().text());
        assertEquals("follow later", paused.cues().getLast().text());
    }

    @Test
    void keepsSegmentFallbackWhenWhisperHasNoAlignedWords() {
        SubtitleTimeline timeline = from(List.of(segment(3.0, 5.0, "Original words")),
                List.of(word(12.0, 13.0, "Not in the segment")));
        assertEquals(1, timeline.cues().size());
        assertEquals("Original words", timeline.cues().getFirst().text());
        assertEquals(3.0, timeline.cues().getFirst().start());
    }

    @Test
    void excludesSilentSegmentsAndTheirWords() {
        var silent = new SubtitleTranscript.Segment(0.0, 2.0, "Imagined speech", 0.9, -1.5);
        SubtitleTimeline timeline = from(List.of(silent, segment(3, 5, "Real speech")), List.of(
                word(0.4, 0.8, "Imagined"), word(1.0, 1.3, "speech"),
                word(3.1, 3.5, "Real"), word(3.6, 3.9, "speech")));
        assertEquals(1, timeline.cues().size());
        assertEquals("Real speech", timeline.cues().getFirst().text());
    }

    @Test
    void punctuationTokensAttachToTheirPrecedingWord() {
        SubtitleTimeline timeline = from(List.of(segment(0, 2.5, "Stop, now!")), List.of(
                word(0.0, 0.4, "Stop"), word(0.4, 0.5, ","),
                word(0.6, 1.1, "now"), word(1.1, 1.2, "!")));
        assertEquals("Stop, now!", timeline.cues().getFirst().text());
    }

    @Test
    void restoresCommasAndSentencePunctuationFromMatchingSegmentWords() {
        SubtitleTimeline timeline = from(
                List.of(segment(0.0, 3.0, "the Red, Red, Red, and the Ducky.")),
                List.of(word(0.0, 0.3, "the"), word(0.3, 0.6, "Red"),
                        word(0.6, 0.9, "Red"), word(0.9, 1.2, "Red"),
                        word(1.2, 1.5, "and"), word(1.5, 1.8, "the"),
                        word(1.8, 2.1, "Ducky")));
        assertEquals("the Red, Red, Red, and the Ducky.",
                timeline.cues().getFirst().text());
    }

    @Test
    void rejoinsHyphenatedWordsWithoutIntroducingSpaces() {
        SubtitleTimeline timeline = from(
                List.of(segment(0.0, 3.0, "A-A-A-A-And look, the X-D!")),
                List.of(word(0.0, 0.18, "A"), word(0.18, 0.36, "A"),
                        word(0.36, 0.54, "A"), word(0.54, 0.72, "A"),
                        word(0.72, 0.95, "And"), word(1.0, 1.3, "look"),
                        word(1.3, 1.5, "the"), word(1.5, 1.7, "X"),
                        word(1.7, 2.0, "D")));
        assertEquals("A-A-A-A-And look, the X-D!",
                timeline.cues().stream().map(SubtitleTimeline.Cue::text)
                        .collect(Collectors.joining(" ")));
    }

    @Test
    void mismatchedSegmentNeverInjectsUnverifiedPunctuationOrWords() {
        SubtitleTimeline timeline = from(
                List.of(segment(0.0, 3.0, "This, is not the same!")),
                List.of(word(0.0, 0.3, "These"), word(0.4, 0.7, "are"),
                        word(0.8, 1.2, "different")));
        assertEquals("These are different", timeline.cues().getFirst().text());
    }

    private static SubtitleTimeline from(List<SubtitleTranscript.Segment> segments,
                                         List<SubtitleTranscript.Word> words) {
        return SubtitleTimeline.fromTranscript(new SubtitleTranscript("", 100.0, segments, words));
    }

    private static SubtitleTranscript.Segment segment(double start, double end, String text) {
        return new SubtitleTranscript.Segment(start, end, text, 0.0, 0.0);
    }

    private static SubtitleTranscript.Word word(double start, double end, String text) {
        return new SubtitleTranscript.Word(start, end, text);
    }
}
