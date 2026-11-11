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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Failure-injection regressions for delayed speech-to-text responses and missing media.
 * No JavaFX, network, OpenAI credentials, or native FFmpeg decoder is required.
 * The production session/coordinator are exercised through their existing test seams.
 */
class SubtitleResilienceRegressionTest {
    @TempDir
    Path directory;

    private static final SubtitleTranscript FIRST = transcript("First", 1.0, 2.0);
    private static final SubtitleTranscript SECOND = transcript("Second", 4.0, 5.0);

    private static SubtitleTranscript transcript(String text, double start, double end) {
        return new SubtitleTranscript(text, 10.0,
                List.of(new SubtitleTranscript.Segment(start, end, text, 0.0, -0.1)), List.of());
    }

    /** Simulates an HTTP request already in progress that cannot be cancelled. */
    private static final class UncancellableResponse extends CompletableFuture<SubtitleTranscript> {
        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }
    }

    @Test
    void lateUncancellableOldVideoResponseCannotReplaceNewVideoCaptions() {
        UncancellableResponse slowOldVideo = new UncancellableResponse();
        List<Path> requested = new ArrayList<>();
        try (SubtitlePlaybackSession session = new SubtitlePlaybackSession(path -> {
            requested.add(path);
            return path.toString().endsWith("old.mp4")
                    ? slowOldVideo : CompletableFuture.completedFuture(SECOND);
        }, () -> {})) {
            session.setEnabled(true);
            session.load(Path.of("old.mp4"));
            session.load(Path.of("new.mp4"));
            assertEquals(SubtitlePlaybackSession.RequestState.READY, session.requestState());
            assertEquals("Second", session.timeline().atSeconds(4.5).orElseThrow().text());

            slowOldVideo.complete(FIRST);
            assertEquals(SubtitlePlaybackSession.RequestState.READY, session.requestState());
            assertFalse(session.timeline().atSeconds(1.5).isPresent());
            assertEquals("Second", session.timeline().atSeconds(4.5).orElseThrow().text());
            assertEquals(List.of(Path.of("old.mp4"), Path.of("new.mp4")), requested);
        }
    }

    @Test
    void lateOldVideoFailureCannotOverwriteNewVideoSuccess() {
        UncancellableResponse slowOldVideo = new UncancellableResponse();
        try (SubtitlePlaybackSession session = new SubtitlePlaybackSession(path ->
                path.toString().endsWith("old.mp4")
                        ? slowOldVideo : CompletableFuture.completedFuture(SECOND), () -> {})) {
            session.setEnabled(true);
            session.load(Path.of("old.mp4"));
            session.load(Path.of("new.mp4"));
            slowOldVideo.completeExceptionally(new IOException("old server failure"));
            assertEquals(SubtitlePlaybackSession.RequestState.READY, session.requestState());
            assertEquals(null, session.lastFailure());
            assertEquals("Second", session.timeline().atSeconds(4.5).orElseThrow().text());
        }
    }

    @Test
    void arbitraryForwardAndBackwardSeekDoesNotRequestTranscriptionAgain() {
        AtomicInteger requests = new AtomicInteger();
        SubtitleTranscript twoPhrases = new SubtitleTranscript("First Second", 10.0,
                List.of(new SubtitleTranscript.Segment(1.0, 2.0, "First", 0.0, -0.1),
                        new SubtitleTranscript.Segment(4.0, 5.0, "Second", 0.0, -0.1)), List.of());
        try (SubtitlePlaybackSession session = new SubtitlePlaybackSession(path -> {
            requests.incrementAndGet();
            return CompletableFuture.completedFuture(twoPhrases);
        }, () -> {})) {
            session.setEnabled(true);
            session.load(Path.of("movie.mp4"));
            for (int i = 0; i < 20; i++) {
                assertEquals("Second", session.timeline().atSeconds(4.5).orElseThrow().text());
                assertEquals("First", session.timeline().atSeconds(1.5).orElseThrow().text());
                assertTrue(session.timeline().atSeconds(3.0).isEmpty());
            }
            assertEquals(1, requests.get(), "Seeking only reads the published subtitle timeline");
        }
    }

    @Test
    void repeatedTogglesDuringSlowUploadNeverIssueDuplicateRequest() {
        AtomicInteger requests = new AtomicInteger();
        CompletableFuture<SubtitleTranscript> slowServer = new CompletableFuture<>();
        try (SubtitlePlaybackSession session = new SubtitlePlaybackSession(path -> {
            requests.incrementAndGet();
            return slowServer;
        }, () -> {})) {
            session.load(Path.of("movie.mp4"));
            session.setEnabled(true);
            for (int i = 0; i < 20; i++) {
                session.setEnabled(false);
                session.setEnabled(true);
            }
            assertEquals(1, requests.get());
            assertEquals(SubtitlePlaybackSession.RequestState.LOADING, session.requestState());
            slowServer.complete(FIRST);
            assertEquals(SubtitlePlaybackSession.RequestState.READY, session.requestState());
            assertEquals("First", session.timeline().atSeconds(1.5).orElseThrow().text());
        }
    }

    @Test
    void serverFailureDoesNotPoisonNextMediaWithoutDisablingSubtitles() {
        AtomicInteger requests = new AtomicInteger();
        try (SubtitlePlaybackSession session = new SubtitlePlaybackSession(path -> {
            requests.incrementAndGet();
            return path.toString().endsWith("unavailable.mp4")
                    ? CompletableFuture.failedFuture(new IOException("server unavailable"))
                    : CompletableFuture.completedFuture(SECOND);
        }, () -> {})) {
            session.setEnabled(true);
            session.load(Path.of("unavailable.mp4"));
            assertEquals(SubtitlePlaybackSession.RequestState.FAILED, session.requestState());
            assertNotNull(session.lastFailure());
            assertTrue(session.timeline().cues().isEmpty());
            session.load(Path.of("new.mp4"));
            assertEquals(SubtitlePlaybackSession.RequestState.READY, session.requestState());
            assertEquals("Second", session.timeline().atSeconds(4.5).orElseThrow().text());
            assertEquals(2, requests.get());
        }
    }

    @Test
    void closingBeforeUncancellableResponseReturnsKeepsTimelineEmpty() {
        UncancellableResponse server = new UncancellableResponse();
        AtomicInteger resourcesClosed = new AtomicInteger();
        SubtitlePlaybackSession session = new SubtitlePlaybackSession(path -> server,
                resourcesClosed::incrementAndGet);
        session.setEnabled(true);
        session.load(Path.of("movie.mp4"));
        session.close();
        server.complete(FIRST);
        assertFalse(session.isEnabled());
        assertTrue(session.timeline().cues().isEmpty());
        assertEquals(SubtitlePlaybackSession.RequestState.IDLE, session.requestState());
        assertEquals(1, resourcesClosed.get());
    }

    @Test
    void removedMediaFailsCleanlyWithoutInvokingNetworkUpload() throws Exception {
        Path movie = Files.writeString(directory.resolve("movie.mp4"), "media");
        AtomicInteger attemptedUploads = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        CountDownLatch extractionStarted = new CountDownLatch(1);
        CountDownLatch resumeExtraction = new CountDownLatch(1);
        try (SubtitleTranscriptionCoordinator coordinator = new SubtitleTranscriptionCoordinator(path -> {
            extractionStarted.countDown();
            try {
                if (!resumeExtraction.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Test extraction timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Extraction interrupted", interrupted);
            }
            // Simulate a removable/network drive disappearing between video open and extraction.
            Files.readAllBytes(path);
            return Files.writeString(directory.resolve("unused.wav"), "audio");
        }, wav -> {
            attemptedUploads.incrementAndGet();
            return CompletableFuture.completedFuture(FIRST);
        }, worker)) {
            CompletableFuture<SubtitleTranscript> result = coordinator.transcribe(movie);
            assertTrue(extractionStarted.await(5, TimeUnit.SECONDS));
            Files.delete(movie);
            resumeExtraction.countDown();
            try {
                result.join();
                throw new AssertionError("Missing media should fail subtitle extraction");
            } catch (java.util.concurrent.CompletionException expected) {
                assertTrue(expected.getCause() instanceof IOException);
            }
            assertEquals(0, attemptedUploads.get(), "Missing media must never be uploaded");
        } finally {
            resumeExtraction.countDown();
            worker.shutdownNow();
        }
    }

    @Test
    void cancellingQueuedMediaSkipsExtractionAndUpload() throws Exception {
        Path mediaA = directory.resolve("a.mp4");
        Path mediaB = directory.resolve("b.mp4");
        Path wavA = directory.resolve("a.wav");
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger secondExtracts = new AtomicInteger();
        AtomicInteger secondUploads = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (SubtitleTranscriptionCoordinator coordinator = new SubtitleTranscriptionCoordinator(path -> {
            if (path.equals(mediaB)) {
                secondExtracts.incrementAndGet();
                return Files.writeString(directory.resolve("b.wav"), "data");
            }
            firstStarted.countDown();
            try {
                if (!releaseFirst.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Test extraction timed out");
                }
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("Extraction interrupted", interrupted);
            }
            return Files.writeString(wavA, "data");
        }, wav -> {
            if (wav.endsWith("b.wav")) {
                secondUploads.incrementAndGet();
            }
            return CompletableFuture.completedFuture(FIRST);
        }, worker)) {
            CompletableFuture<SubtitleTranscript> first = coordinator.transcribe(mediaA);
            assertTrue(firstStarted.await(5, TimeUnit.SECONDS));
            CompletableFuture<SubtitleTranscript> second = coordinator.transcribe(mediaB);
            assertTrue(second.cancel(false));
            releaseFirst.countDown();
            assertEquals(FIRST, first.get(5, TimeUnit.SECONDS));
            // This runs after both preceding extraction tasks on the single executor.
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertEquals(0, secondExtracts.get());
            assertEquals(0, secondUploads.get());
            assertFalse(Files.exists(wavA), "Completed uploads must clean up their WAV");
            assertFalse(Files.exists(directory.resolve("b.wav")));
        } finally {
            releaseFirst.countDown();
            worker.shutdownNow();
        }
    }
}
