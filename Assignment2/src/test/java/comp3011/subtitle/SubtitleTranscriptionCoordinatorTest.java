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
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubtitleTranscriptionCoordinatorTest {
    @TempDir
    Path directory;

    private static final SubtitleTranscript TRANSCRIPT = new SubtitleTranscript(
            "Hello", 1.5,
            List.of(new SubtitleTranscript.Segment(0.2, 1.2, "Hello", 0.1, -0.2)),
            List.of(new SubtitleTranscript.Word(0.2, 1.0, "Hello")));

    @Test
    void extractsOnBackgroundThreadAndKeepsWavUntilUploadFinishes() throws Exception {
        Path media = Files.writeString(directory.resolve("movie.mp4"), "unchanged original");
        Path wav = directory.resolve("extracted.wav");
        AtomicReference<Thread> extractionThread = new AtomicReference<>();
        CompletableFuture<Path> received = new CompletableFuture<>();
        CompletableFuture<SubtitleTranscript> server = new CompletableFuture<>();

        try (SubtitleTranscriptionCoordinator coordinator = coordinator(path -> {
            extractionThread.set(Thread.currentThread());
            assertEquals(media, path);
            return Files.writeString(wav, "test audio");
        }, path -> {
            received.complete(path);
            return server;
        })) {
            CompletableFuture<SubtitleTranscript> outcome = coordinator.transcribe(media);
            assertEquals(wav, received.get(5, TimeUnit.SECONDS));
            assertNotEquals(Thread.currentThread(), extractionThread.get());
            assertFalse(outcome.isDone(), "Result must wait for the HTTP request");
            assertTrue(Files.exists(wav), "HTTP body still needs the WAV while uploading");

            server.complete(TRANSCRIPT);
            assertEquals(TRANSCRIPT, outcome.get(5, TimeUnit.SECONDS));
            assertFalse(Files.exists(wav), "Completed upload must remove temporary audio");
            assertEquals("unchanged original", Files.readString(media));
        }
    }

    @Test
    void extractionFailureDoesNotAttemptUpload() throws Exception {
        AtomicInteger uploads = new AtomicInteger();
        try (SubtitleTranscriptionCoordinator coordinator = coordinator(path -> {
            throw new IOException("Media has no audio");
        }, wav -> {
            uploads.incrementAndGet();
            return CompletableFuture.completedFuture(TRANSCRIPT);
        })) {
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> coordinator.transcribe(directory.resolve("bad.mp4")).join());
            assertTrue(failure.getCause() instanceof IOException);
            assertEquals(0, uploads.get());
        }
    }

    @Test
    void unsuccessfulUploadStillDeletesExtractedAudio() throws Exception {
        Path wav = directory.resolve("failed.wav");
        try (SubtitleTranscriptionCoordinator coordinator = coordinator(path ->
                Files.writeString(wav, "test audio"),
                path -> CompletableFuture.failedFuture(new IOException("Server unavailable")))) {
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> coordinator.transcribe(directory.resolve("movie.mp4")).join());
            assertTrue(failure.getCause() instanceof IOException);
            assertFalse(Files.exists(wav));
        }
    }

    @Test
    void synchronousUploadSetupFailureAlsoDeletesAudio() throws Exception {
        Path wav = directory.resolve("rejected.wav");
        try (SubtitleTranscriptionCoordinator coordinator = coordinator(path ->
                Files.writeString(wav, "test audio"), path -> {
                    throw new IllegalStateException("Cannot create upload");
                })) {
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> coordinator.transcribe(directory.resolve("movie.mp4")).join());
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertFalse(Files.exists(wav));
        }
    }

    @Test
    void cancelledResultBeforeExtractionFinishesSkipsUploadAndRemovesWav() throws Exception {
        Path wav = directory.resolve("cancelled.wav");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger uploads = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (SubtitleTranscriptionCoordinator coordinator = new SubtitleTranscriptionCoordinator(path -> {
            started.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IOException("Timed out waiting for test");
                }
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted", interruption);
            }
            return Files.writeString(wav, "test audio");
        }, path -> {
            uploads.incrementAndGet();
            return CompletableFuture.completedFuture(TRANSCRIPT);
        }, worker)) {
            CompletableFuture<SubtitleTranscript> outcome = coordinator.transcribe(directory.resolve("movie.mp4"));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            assertTrue(outcome.cancel(false));
            release.countDown();
            // A barrier on the single extraction executor ensures cleanup has run.
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
            assertFalse(Files.exists(wav));
            assertEquals(0, uploads.get());
        } finally {
            release.countDown();
        }
    }

    @Test
    void closeCancelsResultsButDoesNotDeleteAudioBeingUploaded() throws Exception {
        Path wav = directory.resolve("uploading.wav");
        CompletableFuture<Path> received = new CompletableFuture<>();
        CompletableFuture<SubtitleTranscript> server = new CompletableFuture<>();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        SubtitleTranscriptionCoordinator coordinator = new SubtitleTranscriptionCoordinator(path ->
                Files.writeString(wav, "test audio"), path -> {
                    received.complete(path);
                    return server;
                }, worker);
        try {
            CompletableFuture<SubtitleTranscript> outcome = coordinator.transcribe(directory.resolve("movie.mp4"));
            received.get(5, TimeUnit.SECONDS);
            // The upload callback is attached after the upload function returns.
            // Wait for the extraction task to finish registering that callback
            // before completing the simulated HTTP request.
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
            coordinator.close();
            assertTrue(outcome.isCancelled());
            assertTrue(Files.exists(wav), "Do not remove audio while HTTP might be reading it");
            server.complete(TRANSCRIPT);
            assertFalse(Files.exists(wav));
            assertThrows(CompletionException.class,
                    () -> coordinator.transcribe(directory.resolve("new.mp4")).join());
        } finally {
            server.complete(TRANSCRIPT);
            coordinator.close();
        }
    }

    private static SubtitleTranscriptionCoordinator coordinator(
            SubtitleTranscriptionCoordinator.AudioExtraction extraction,
            java.util.function.Function<Path, CompletableFuture<SubtitleTranscript>> upload) {
        return new SubtitleTranscriptionCoordinator(extraction, upload,
                Executors.newSingleThreadExecutor());
    }
}
