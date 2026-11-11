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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SubtitleTimelineTest {

    @Test
    void displaysPhrasesAtTheirTimestampsAndNotDuringGaps() {
        SubtitleTimeline timeline = timeline(
                segment(0.5, 2.0, "  Hello   there  "),
                segment(4.0, 5.5, "General Kenobi."));

        assertTrue(timeline.atSeconds(0.49).isEmpty());
        assertEquals("Hello there", textAt(timeline, 0.5));
        assertEquals("Hello there", textAt(timeline, 1.99));
        assertTrue(timeline.atSeconds(2.0).isEmpty(), "End is exclusive");
        assertTrue(timeline.atSeconds(3.0).isEmpty());
        assertEquals("General Kenobi.", textAt(timeline, 4.0));
        assertTrue(timeline.atSeconds(5.5).isEmpty());
    }

    @Test
    void permitsSeekingBackwardsAndForwardsWithoutCursorState() {
        SubtitleTimeline timeline = timeline(
                segment(6.0, 7.0, "Third"),
                segment(1.0, 2.0, "First"),
                segment(3.0, 4.0, "Second"));

        assertEquals("Third", textAt(timeline, 6.2));
        assertEquals("First", textAt(timeline, 1.2));
        assertEquals("Second", textAt(timeline, 3.2));
        assertEquals("First", textAt(timeline, 1.0));
        assertEquals(3, timeline.cues().size());
        assertEquals(1.0, timeline.cues().getFirst().start());
    }

    @Test
    void extendsShortPhrasesButDoesNotExceedSevenSeconds() {
        SubtitleTimeline timeline = timeline(
                segment(0.0, 0.25, "Short"),
                segment(3.0, 30.0, "Long"));

        assertEquals(1.0, timeline.cues().getFirst().end());
        assertEquals("Short", textAt(timeline, 0.99));
        assertTrue(timeline.atSeconds(1.0).isEmpty());
        assertEquals(10.0, timeline.cues().get(1).end());
        assertEquals("Long", textAt(timeline, 9.99));
        assertTrue(timeline.atSeconds(10.0).isEmpty());
    }

    @Test
    void clipsOverlapsSoEarlierPhrasesDoNotReturnAfterSeeking() {
        SubtitleTimeline timeline = timeline(
                segment(0.0, 5.0, "Previous"),
                segment(2.0, 2.2, "New"));

        assertEquals(2.0, timeline.cues().getFirst().end());
        assertEquals(3.0, timeline.cues().get(1).end());
        assertEquals("Previous", textAt(timeline, 1.9));
        assertEquals("New", textAt(timeline, 2.0));
        assertEquals("New", textAt(timeline, 2.99));
        assertTrue(timeline.atSeconds(3.0).isEmpty());
        assertEquals("Previous", textAt(timeline, 0.5));
        assertTrue(timeline.atSeconds(4.0).isEmpty());
    }

    @Test
    void nearbyPhrasesTakePriorityEvenWhenThatShortensTheMinimumHold() {
        SubtitleTimeline timeline = timeline(
                segment(0.0, 0.1, "First"),
                segment(0.4, 0.6, "Second"));

        assertEquals(0.4, timeline.cues().getFirst().end());
        assertEquals("Second", textAt(timeline, 0.4));
        assertEquals(1.4, timeline.cues().get(1).end(), 0.0000001);
    }

    @Test
    void removesBlankOrConfidentlySilentSegments() {
        SubtitleTimeline timeline = timeline(
                segment(0.0, 1.0, "   "),
                new SubtitleTranscript.Segment(1.0, 2.0, "Silence", 0.7, -1.2),
                new SubtitleTranscript.Segment(2.0, 3.0, "Keep on probability boundary", 0.6, -1.2),
                new SubtitleTranscript.Segment(4.0, 5.0, "Keep on log boundary", 0.7, -1.0));

        assertEquals(2, timeline.cues().size());
        assertTrue(timeline.atSeconds(1.5).isEmpty());
        assertEquals("Keep on probability boundary", textAt(timeline, 2.5));
        assertEquals("Keep on log boundary", textAt(timeline, 4.5));
    }

    @Test
    void mostRecentInputWinsWhenSegmentsHaveIdenticalStartTimes() {
        SubtitleTimeline timeline = timeline(
                segment(1.0, 3.0, "Original"),
                segment(1.0, 2.0, "Correction"));

        assertEquals(1, timeline.cues().size());
        assertEquals("Correction", textAt(timeline, 1.5));
    }

    @Test
    void emptyOrUntimedTranscriptDoesNotInventDialogueTiming() {
        SubtitleTimeline empty = SubtitleTimeline.fromTranscript(new SubtitleTranscript(
                "Transcript without segments", 5.0, List.of(), List.of()));

        assertTrue(empty.cues().isEmpty());
        assertTrue(empty.atSeconds(0).isEmpty());
        assertTrue(empty.atSeconds(10).isEmpty());
        assertThrows(NullPointerException.class, () -> SubtitleTimeline.fromTranscript(null));
    }

    @Test
    void invalidPlaybackTimesReturnNoCaptionAndCuesCannotBeModified() {
        SubtitleTimeline timeline = timeline(segment(0.0, 2.0, "Hello"));

        assertTrue(timeline.atSeconds(-1).isEmpty());
        assertTrue(timeline.atSeconds(Double.NaN).isEmpty());
        assertTrue(timeline.atSeconds(Double.POSITIVE_INFINITY).isEmpty());
        assertTrue(timeline.atSeconds(Double.NEGATIVE_INFINITY).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> timeline.cues().clear());
        assertFalse(timeline.cues().isEmpty());
    }

    @Test
    void zeroLengthRecognisedPhraseReceivesAVisibleInterval() {
        SubtitleTimeline timeline = timeline(segment(5.0, 5.0, "Now"));

        assertEquals(6.0, timeline.cues().getFirst().end());
        assertEquals("Now", textAt(timeline, 5.0));
        assertTrue(timeline.atSeconds(6.0).isEmpty());
    }

    @Test
    void mergesContiguousFlashCuesButPreservesAllTheirWordsAndOriginalStart() {
        SubtitleTimeline timeline = timeline(
                segment(2.0, 2.24, "War"),
                segment(2.24, 2.26, "War!"),
                segment(2.26, 3.15, "It's a war."));
        assertEquals(1, timeline.cues().size());
        assertEquals("War War! It's a war.", timeline.cues().getFirst().text());
        assertEquals(2.0, timeline.cues().getFirst().start(), 1e-9);
        assertEquals(3.15, timeline.cues().getFirst().end(), 1e-9);
    }

    @Test
    void neverMergesBriefQuestionWithAnswerOrAcrossSpeechPause() {
        SubtitleTimeline timeline = timeline(
                segment(0.0, 0.3, "What?"),
                segment(0.3, 1.1, "I don't know."),
                segment(1.5, 1.7, "Next"),
                segment(1.95, 2.15, "phrase"));
        assertEquals(4, timeline.cues().size());
        assertEquals("What?", timeline.cues().getFirst().text());
        assertEquals("I don't know.", timeline.cues().get(1).text());
        assertEquals("Next", timeline.cues().get(2).text());
        assertEquals("phrase", timeline.cues().get(3).text());
    }

    @Test
    void doesNotMergeShortCaptionIntoLongFutureDialogue() {
        SubtitleTimeline timeline = timeline(
                segment(0.0, 0.25, "Oh yeah!"),
                segment(0.25, 4.5, "The future is a much longer statement."));
        assertEquals(2, timeline.cues().size());
        assertEquals("Oh yeah!", timeline.cues().getFirst().text());
        assertEquals(0.25, timeline.cues().getFirst().end(), 1e-9);
    }

    private static SubtitleTimeline timeline(SubtitleTranscript.Segment... segments) {
        return SubtitleTimeline.fromTranscript(new SubtitleTranscript(
                "", 40.0, List.of(segments), List.of()));
    }

    private static SubtitleTranscript.Segment segment(double start, double end, String text) {
        return new SubtitleTranscript.Segment(start, end, text, 0.0, -0.2);
    }

    private static String textAt(SubtitleTimeline timeline, double seconds) {
        Optional<SubtitleTimeline.Cue> cue = timeline.atSeconds(seconds);
        assertTrue(cue.isPresent(), "Expected a subtitle at " + seconds + "s");
        return cue.orElseThrow().text();
    }
}
