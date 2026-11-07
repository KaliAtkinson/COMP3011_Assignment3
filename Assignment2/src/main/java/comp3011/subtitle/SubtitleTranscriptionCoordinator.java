package comp3011.subtitle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Connects original-media audio extraction with the asynchronous subtitle server.
 *
 * <p>Extracting audio is blocking, so it runs on a single dedicated background thread,
 * independent of the video decoder and JavaFX application thread. Upload and JSON
 * decoding are asynchronous in {@link SubtitleApiClient}. The temporary WAV stays
 * available for the whole upload, and is deleted before the result is published.
 * Cancelling a request prevents unnecessary future work, but an upload already in
 * progress is allowed to finish so its file is not deleted while HTTP is reading it.
 *
 * <p>Each call is independent: the player can cancel an obsolete result when a new
 * video is opened. Toggling subtitles off does not require cancelling transcription.
 * Closing the coordinator cancels outstanding results and interrupts extraction.
 *
 * <p>AI Assistance: Developed with advice and code assistance from OpenAI ChatGPT.
 */
public final class SubtitleTranscriptionCoordinator implements AutoCloseable {

    @FunctionalInterface
    interface AudioExtraction {
        Path extract(Path mediaFile) throws IOException;
    }

    private final AudioExtraction extraction;
    private final Function<Path, CompletableFuture<SubtitleTranscript>> upload;
    private final ExecutorService extractionExecutor;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<CompletableFuture<SubtitleTranscript>> requests = ConcurrentHashMap.newKeySet();

    public SubtitleTranscriptionCoordinator() {
        this(new SubtitleAudioExtractor()::extract,
                new SubtitleApiClient()::transcribe,
                Executors.newSingleThreadExecutor(task -> {
                    Thread thread = new Thread(task, "subtitle-audio-extraction");
                    thread.setDaemon(true);
                    return thread;
                }));
    }

    // Injectable stages make failure, timing and resource ownership testable offline.
    SubtitleTranscriptionCoordinator(
            AudioExtraction extraction,
            Function<Path, CompletableFuture<SubtitleTranscript>> upload,
            ExecutorService extractionExecutor) {
        this.extraction = Objects.requireNonNull(extraction, "extraction");
        this.upload = Objects.requireNonNull(upload, "upload");
        this.extractionExecutor = Objects.requireNonNull(extractionExecutor, "extractionExecutor");
    }

    /**
     * Starts extraction and transcription without blocking the caller.
     *
     * <p>Completes exceptionally on decoding, HTTP or cleanup failure; the player
     * must handle failures as unavailable subtitles rather than stopping playback.
     * Cancelling the returned future stops work that has not yet reached upload.
     */
    public CompletableFuture<SubtitleTranscript> transcribe(Path mediaFile) {
        Objects.requireNonNull(mediaFile, "mediaFile");
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Subtitle coordinator is closed"));
        }

        CompletableFuture<SubtitleTranscript> result = new CompletableFuture<>();
        requests.add(result);
        result.whenComplete((ignored, failure) -> requests.remove(result));

        // A concurrent close() might have happened after the first closed check.
        if (closed.get()) {
            result.cancel(false);
            return result;
        }
        try {
            extractionExecutor.execute(() -> extractAndUpload(mediaFile, result));
        } catch (RejectedExecutionException rejected) {
            result.completeExceptionally(new IllegalStateException("Subtitle extraction cannot start", rejected));
        }
        return result;
    }

    private void extractAndUpload(Path mediaFile, CompletableFuture<SubtitleTranscript> result) {
        Path temporaryWav = null;
        Throwable failure = null;
        try {
            if (result.isDone() || closed.get()) {
                return;
            }
            temporaryWav = Objects.requireNonNull(extraction.extract(mediaFile), "extracted WAV");
            if (result.isDone() || closed.get()) {
                return;
            }

            CompletableFuture<SubtitleTranscript> response = Objects.requireNonNull(
                    upload.apply(temporaryWav), "subtitle upload future");
            Path uploadedWav = temporaryWav;
            temporaryWav = null; // Upload completion now owns cleanup.
            response.whenComplete((transcript, error) -> completeUpload(result, uploadedWav, transcript, error));
        } catch (Exception exception) {
            failure = exception;
        } finally {
            if (temporaryWav != null) {
                try {
                    Files.deleteIfExists(temporaryWav);
                } catch (IOException cleanupFailure) {
                    if (failure == null) {
                        failure = cleanupFailure;
                    } else {
                        failure.addSuppressed(cleanupFailure);
                    }
                }
            }
            if (failure != null) {
                result.completeExceptionally(failure);
            }
        }
    }

    private static void completeUpload(CompletableFuture<SubtitleTranscript> result,
                                       Path temporaryWav, SubtitleTranscript transcript,
                                       Throwable uploadFailure) {
        Throwable failure = uploadFailure;
        try {
            Files.deleteIfExists(temporaryWav);
        } catch (IOException cleanupFailure) {
            if (failure == null) {
                failure = cleanupFailure;
            } else {
                failure.addSuppressed(cleanupFailure);
            }
        }

        if (failure != null) {
            result.completeExceptionally(failure);
        } else {
            result.complete(transcript);
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            // Cancellation marks the results stale; in-flight HTTP callbacks still
            // own their WAV file and clean it up when the upload completes.
            for (CompletableFuture<SubtitleTranscript> request : requests) {
                request.cancel(false);
            }
            extractionExecutor.shutdownNow();
        }
    }
}
