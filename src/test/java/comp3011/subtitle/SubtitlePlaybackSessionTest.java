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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class SubtitlePlaybackSessionTest {
    private static final SubtitleTranscript CAPTIONS = new SubtitleTranscript(
            "Hello", 5,
            List.of(new SubtitleTranscript.Segment(1, 2, "Hello", 0, 0)),
            List.of());

    @Test
    void reportsRequestLifecycleWithoutConfusingEmptyAndFailedResults() {
        CompletableFuture<SubtitleTranscript> response = new CompletableFuture<>();
        try (SubtitlePlaybackSession session = new SubtitlePlaybackSession(path -> response, () -> {})) {
            assertEquals(SubtitlePlaybackSession.RequestState.IDLE, session.requestState());
            session.load(Path.of("movie.mp4"));
            session.setEnabled(true);
            assertEquals(SubtitlePlaybackSession.RequestState.LOADING, session.requestState());
            response.complete(new SubtitleTranscript("", 0, List.of(), List.of()));
            assertEquals(SubtitlePlaybackSession.RequestState.READY, session.requestState());
            assertTrue(session.timeline().cues().isEmpty());
            session.load(null);
            assertEquals(SubtitlePlaybackSession.RequestState.IDLE, session.requestState());
        }
    }

    @Test
    void reportsFailureAndRetryWhenCaptionsAreReenabled() {
        AtomicInteger requests = new AtomicInteger();
        try (SubtitlePlaybackSession session = new SubtitlePlaybackSession(path -> {
            if (requests.getAndIncrement() == 0) {
                return CompletableFuture.failedFuture(new IOException("server unavailable"));
            }
            return CompletableFuture.completedFuture(CAPTIONS);
        }, () -> {})) {
            session.load(Path.of("movie.mp4"));
            session.setEnabled(true);
            assertEquals(SubtitlePlaybackSession.RequestState.FAILED, session.requestState());
            session.setEnabled(false);
            session.setEnabled(true);
            assertEquals(SubtitlePlaybackSession.RequestState.READY, session.requestState());
            assertEquals(2, requests.get());
        }
    }

    @Test
    void doesNotTranscribeUntilEnabledAndPublishesResultWithoutRestart() {
        AtomicInteger requests = new AtomicInteger();
        CompletableFuture<SubtitleTranscript> server = new CompletableFuture<>();
        try (SubtitlePlaybackSession session = new SubtitlePlaybackSession(path -> {
            requests.incrementAndGet();
            return server;
        }, () -> {})) {
            session.load(Path.of("movie.mp4"));
            assertEquals(0, requests.get(), "Subtitles are opt-in and cost money");
            assertTrue(session.timeline().cues().isEmpty());

            session.setEnabled(true);
            assertEquals(1, requests.get());
            assertTrue(session.timeline().cues().isEmpty());
            server.complete(CAPTIONS);
            assertEquals("Hello", session.timeline().atSeconds(1.5).orElseThrow().text());
            session.setEnabled(false);
            assertFalse(session.isEnabled());
            session.setEnabled(true);
            assertEquals(1, requests.get(), "Toggling must reuse the successful transcript");
        }
    }

    @Test
    void turningOffDuringUploadKeepsResultReadyForLater() {
        CompletableFuture<SubtitleTranscript> response = new CompletableFuture<>();
        try (SubtitlePlaybackSession session = new SubtitlePlaybackSession(path -> response, () -> {})) {
            session.setEnabled(true);
            session.load(Path.of("movie.mp4"));
            session.setEnabled(false);
            response.complete(CAPTIONS);
            assertFalse(session.isEnabled());
            assertFalse(session.timeline().cues().isEmpty());
            session.setEnabled(true);
            assertEquals("Hello", session.timeline().atSeconds(1.5).orElseThrow().text());
        }
    }

    @Test
    void replacingVideoRejectsLateResponseFromOldVideo() {
        List<CompletableFuture<SubtitleTranscript>> uploads = new ArrayList<>();
        try (SubtitlePlaybackSession session = new SubtitlePlaybackSession(path -> {
            CompletableFuture<SubtitleTranscript> future = new CompletableFuture<>();
            uploads.add(future);
            return future;
        }, () -> {})) {
            session.setEnabled(true);
            session.load(Path.of("first.mp4"));
            session.load(Path.of("second.mp4"));
            assertEquals(2, uploads.size());
            assertTrue(uploads.getFirst().isCancelled());
            uploads.get(1).complete(CAPTIONS);
            assertEquals("Hello", session.timeline().atSeconds(1.2).orElseThrow().text());
            session.load(null);
            assertTrue(session.timeline().cues().isEmpty());
        }
    }

    @Test
    void failureDoesNotInterruptVideoAndNewEnableRetries() {
        AtomicInteger requests = new AtomicInteger();
        try (SubtitlePlaybackSession session = new SubtitlePlaybackSession(path -> {
            if (requests.incrementAndGet() == 1) {
                return CompletableFuture.failedFuture(new IOException("server offline"));
            }
            return CompletableFuture.completedFuture(CAPTIONS);
        }, () -> {})) {
            session.load(Path.of("movie.mp4"));
            session.setEnabled(true);
            assertTrue(session.timeline().cues().isEmpty());
            assertNotNull(session.lastFailure());
            session.setEnabled(false);
            session.setEnabled(true);
            assertEquals(2, requests.get());
            assertEquals("Hello", session.timeline().atSeconds(1.2).orElseThrow().text());
        }
    }

    @Test
    void shutdownCancelsPendingRequestAndClosesCoordinator() {
        AtomicInteger closes = new AtomicInteger();
        CompletableFuture<SubtitleTranscript> server = new CompletableFuture<>();
        SubtitlePlaybackSession session = new SubtitlePlaybackSession(path -> server, closes::incrementAndGet);
        session.setEnabled(true);
        session.load(Path.of("movie.mp4"));
        session.close();
        session.close();
        assertEquals(1, closes.get());
        assertTrue(server.isCancelled());
        assertFalse(session.isEnabled());
        assertTrue(session.timeline().cues().isEmpty());
    }
}
