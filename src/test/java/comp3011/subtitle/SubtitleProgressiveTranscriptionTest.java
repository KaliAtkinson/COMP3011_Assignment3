/*
 * AI Assistance:
 * This test suite was developed and refined with advice, guidance,
 * and code assistance from OpenAI ChatGPT.
 * Test implementations were reviewed and validated as part of
 * the development process.
 */
package comp3011.subtitle;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubtitleProgressiveTranscriptionTest {
    @TempDir Path temp;

    private static SubtitleTranscript transcript(String text, double start) {
        return new SubtitleTranscript(text, start + 3,
                List.of(new SubtitleTranscript.Segment(start, start + 1, text, 0, 0)),
                List.of(new SubtitleTranscript.Word(start, start + 1, text)));
    }

    @Test
    void openingCaptionsArriveBeforeFullUploadAndBothFilesAreRemoved() throws Exception {
        Path previewWav = temp.resolve("preview.wav");
        Path fullWav = temp.resolve("full.wav");
        Path video = temp.resolve("movie.mp4");
        CompletableFuture<SubtitleTranscript> previewHttp = new CompletableFuture<>();
        CompletableFuture<SubtitleTranscript> fullHttp = new CompletableFuture<>();
        CompletableFuture<SubtitleTranscript> published = new CompletableFuture<>();
        CompletableFuture<Void> bothStarted = new CompletableFuture<>();
        AtomicInteger uploads = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (SubtitleTranscriptionCoordinator coordinator = new SubtitleTranscriptionCoordinator(
                media -> Files.writeString(fullWav, "full samples"),
                media -> Files.writeString(previewWav, "opening samples"),
                wav -> {
                    if (uploads.incrementAndGet() == 2) bothStarted.complete(null);
                    return wav.equals(previewWav) ? previewHttp : fullHttp;
                }, worker)) {
            CompletableFuture<SubtitleTranscript> result =
                    coordinator.transcribeProgressively(video, published::complete);
            bothStarted.get(5, TimeUnit.SECONDS);
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS); // callbacks attached
            assertTrue(Files.exists(previewWav));
            assertTrue(Files.exists(fullWav));
            previewHttp.complete(transcript("Opening", 1));
            assertEquals("Opening", published.get(5, TimeUnit.SECONDS).text());
            assertFalse(Files.exists(previewWav));
            assertFalse(result.isDone());
            fullHttp.complete(transcript("Complete", 1));
            assertEquals("Complete", result.get(5, TimeUnit.SECONDS).text());
            assertFalse(Files.exists(fullWav));
            assertEquals(2, uploads.get());
        }
    }

    @Test
    void previewFailureDoesNotPreventFullTranscription() throws Exception {
        Path fullWav = temp.resolve("fallback.wav");
        AtomicInteger uploads = new AtomicInteger();
        try (SubtitleTranscriptionCoordinator coordinator = new SubtitleTranscriptionCoordinator(
                media -> Files.writeString(fullWav, "full samples"),
                media -> { throw new IOException("preview could not be decoded"); },
                wav -> { uploads.incrementAndGet(); return CompletableFuture.completedFuture(transcript("Full", 0)); },
                Executors.newSingleThreadExecutor())) {
            CompletableFuture<SubtitleTranscript> result = coordinator.transcribeProgressively(
                    temp.resolve("movie.mp4"), ignored -> fail("Failed preview should not publish"));
            assertEquals("Full", result.get(5, TimeUnit.SECONDS).text());
            assertEquals(1, uploads.get());
            assertFalse(Files.exists(fullWav));
        }
    }

    @Test
    void failedPreviewHttpStillCleansUpAndReturnsFullResult() throws Exception {
        Path previewWav = temp.resolve("preview-failed.wav");
        Path fullWav = temp.resolve("full-success.wav");
        CompletableFuture<SubtitleTranscript> previewHttp = new CompletableFuture<>();
        CompletableFuture<SubtitleTranscript> fullHttp = new CompletableFuture<>();
        CompletableFuture<Void> bothStarted = new CompletableFuture<>();
        AtomicInteger count = new AtomicInteger();
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (SubtitleTranscriptionCoordinator coordinator = new SubtitleTranscriptionCoordinator(
                media -> Files.writeString(fullWav, "full"),
                media -> Files.writeString(previewWav, "preview"),
                wav -> {
                    if (count.incrementAndGet() == 2) bothStarted.complete(null);
                    return wav.equals(previewWav) ? previewHttp : fullHttp;
                }, worker)) {
            CompletableFuture<SubtitleTranscript> finished =
                    coordinator.transcribeProgressively(temp.resolve("movie.mp4"),
                            preview -> fail("Failed preview must not be published"));
            bothStarted.get(5, TimeUnit.SECONDS);
            worker.submit(() -> {}).get(5, TimeUnit.SECONDS);
            previewHttp.completeExceptionally(new IOException("slow preview server"));
            assertFalse(Files.exists(previewWav));
            fullHttp.complete(transcript("Full", 1));
            assertEquals("Full", finished.get(5, TimeUnit.SECONDS).text());
            assertFalse(Files.exists(fullWav));
        }
    }

    @Test
    void sessionShowsPreviewAndReplacesItWithFullTranscriptWithoutSeeking() {
        List<Consumer<SubtitleTranscript>> previews = new ArrayList<>();
        CompletableFuture<SubtitleTranscript> full = new CompletableFuture<>();
        try (SubtitlePlaybackSession session = new SubtitlePlaybackSession(
                (SubtitlePlaybackSession.ProgressiveRequest) (media, preview) -> {
                    previews.add(preview);
                    return full;
                }, () -> {})) {
            session.setEnabled(true);
            session.load(temp.resolve("movie.mp4"));
            assertEquals(SubtitlePlaybackSession.RequestState.LOADING, session.requestState());
            previews.getFirst().accept(transcript("Opening", 1));
            assertEquals(SubtitlePlaybackSession.RequestState.PARTIAL, session.requestState());
            assertEquals("Opening", session.timeline().atSeconds(1.2).orElseThrow().text());
            assertTrue(session.timeline().atSeconds(25).isEmpty());
            full.complete(transcript("Full dialogue", 25));
            assertEquals(SubtitlePlaybackSession.RequestState.READY, session.requestState());
            assertTrue(session.timeline().atSeconds(1.2).isEmpty());
            assertEquals("Full dialogue", session.timeline().atSeconds(25.2).orElseThrow().text());
        }
    }

    @Test
    void oldVideoPreviewCannotOverwriteNewerCaptions() {
        List<Consumer<SubtitleTranscript>> previews = new ArrayList<>();
        List<CompletableFuture<SubtitleTranscript>> fullRequests = new ArrayList<>();
        try (SubtitlePlaybackSession session = new SubtitlePlaybackSession(
                (SubtitlePlaybackSession.ProgressiveRequest) (media, preview) -> {
                    previews.add(preview);
                    CompletableFuture<SubtitleTranscript> full = new CompletableFuture<>();
                    fullRequests.add(full);
                    return full;
                }, () -> {})) {
            session.setEnabled(true);
            session.load(temp.resolve("first.mp4"));
            session.load(temp.resolve("second.mp4"));
            previews.getFirst().accept(transcript("Stale", 1));
            assertTrue(session.timeline().cues().isEmpty());
            previews.get(1).accept(transcript("Current", 1));
            assertEquals("Current", session.timeline().atSeconds(1.2).orElseThrow().text());
            fullRequests.get(1).complete(transcript("Complete", 1));
            previews.get(1).accept(transcript("Stale again", 1));
            assertEquals("Complete", session.timeline().atSeconds(1.2).orElseThrow().text());
        }
    }

    @Test
    void fullRequestFailureKeepsAlreadyAvailablePreview() {
        CompletableFuture<SubtitleTranscript> full = new CompletableFuture<>();
        List<Consumer<SubtitleTranscript>> previews = new ArrayList<>();
        try (SubtitlePlaybackSession session = new SubtitlePlaybackSession(
                (SubtitlePlaybackSession.ProgressiveRequest) (media, preview) -> {
                    previews.add(preview);
                    return full;
                }, () -> {})) {
            session.setEnabled(true);
            session.load(temp.resolve("movie.mp4"));
            previews.getFirst().accept(transcript("Available", 1));
            full.completeExceptionally(new IOException("server offline"));
            assertEquals(SubtitlePlaybackSession.RequestState.FAILED, session.requestState());
            assertEquals("Available", session.timeline().atSeconds(1.2).orElseThrow().text());
        }
    }
}
