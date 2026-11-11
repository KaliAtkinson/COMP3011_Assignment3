/*
 * AI Assistance:
 * This test suite was developed and refined with advice, guidance,
 * and code assistance from OpenAI ChatGPT.
 * Test implementations were reviewed and validated as part of
 * the development process.
 */
package comp3011.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class SubtitleDisplayStateTest {
    private static final SubtitleTimeline TIMELINE = SubtitleTimeline.fromTranscript(
            new SubtitleTranscript("First. Second.", 10,
                    List.of(new SubtitleTranscript.Segment(1, 2, "First.", 0, 0),
                            new SubtitleTranscript.Segment(4, 5, "Second.", 0, 0)),
                    List.of()));

    @Test
    void enablingAndDisablingChangesTheVisibleCaptionImmediately() {
        SubtitleDisplayState state = new SubtitleDisplayState();
        assertTrue(state.update(false, TIMELINE, 1_500_000).isEmpty());
        assertEquals(Optional.of("First."), state.update(true, TIMELINE, 1_500_000));
        assertEquals(Optional.of(""), state.update(false, TIMELINE, 1_500_000));
        assertEquals(Optional.of("First."), state.update(true, TIMELINE, 1_500_000));
    }

    @Test
    void pausedPositionUpdatesWhenTranscriptionArrives() {
        SubtitleDisplayState state = new SubtitleDisplayState();
        assertTrue(state.update(true, SubtitleTimeline.empty(), 1_500_000).isEmpty());
        assertEquals(Optional.of("First."), state.update(true, TIMELINE, 1_500_000));
        assertTrue(state.update(true, TIMELINE, 1_500_000).isEmpty(),
                "An unchanged caption must not recreate the image each animation pulse");
    }

    @Test
    void seekingAndGapsClearTheOldCaptionWithoutWaitingForNewFrames() {
        SubtitleDisplayState state = new SubtitleDisplayState();
        assertEquals(Optional.of("First."), state.update(true, TIMELINE, 1_500_000));
        assertEquals(Optional.of(""), state.clear());
        assertTrue(state.clear().isEmpty());
        assertEquals(Optional.of("Second."), state.update(true, TIMELINE, 4_500_000));
        assertEquals(Optional.of(""), state.update(true, TIMELINE, 3_000_000));
        assertEquals(Optional.of("First."), state.update(true, TIMELINE, 1_500_000));
    }

    @Test
    void negativeTimestampShowsNoCaption() {
        SubtitleDisplayState state = new SubtitleDisplayState();
        assertTrue(state.update(true, TIMELINE, -1).isEmpty());
    }
}
