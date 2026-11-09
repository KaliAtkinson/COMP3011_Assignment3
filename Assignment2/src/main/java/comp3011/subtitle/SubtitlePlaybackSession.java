package comp3011.subtitle;

import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * Owns the subtitle request and published timeline for the video currently playing.
 *
 * <p>The JavaFX thread calls {@link #load(Path)} and {@link #setEnabled(boolean)}.
 * The transcription completion can run on an HTTP worker. A short lock protects
 * media replacement and the request generation, so an old response cannot place
 * captions over a newly opened video. The immutable timeline is safely published
 * to the frame-effect workers using a volatile reference.
 *
 * <p>Seeking does not change the media or issue another paid request. Disabling
 * captions hides them without cancelling a useful in-flight transcription.
 *
 * <p>AI Assistance: Developed with advice and code assistance from OpenAI ChatGPT.
 */
public final class SubtitlePlaybackSession implements AutoCloseable {
    /** Observable request state, published across the HTTP and JavaFX threads. */
    public enum RequestState { IDLE, LOADING, READY, FAILED }

    private final Function<Path, CompletableFuture<SubtitleTranscript>> request;
    private final AutoCloseable resource;
    private final Object lock = new Object();

    private volatile boolean enabled;
    private volatile SubtitleTimeline timeline = SubtitleTimeline.empty();
    private Path media;
    private long generation;
    private CompletableFuture<SubtitleTranscript> pending;
    private boolean ready;
    private boolean closed;
    private volatile Throwable lastFailure;
    private volatile RequestState requestState = RequestState.IDLE;

    public SubtitlePlaybackSession() {
        this(new SubtitleTranscriptionCoordinator());
    }

    private SubtitlePlaybackSession(SubtitleTranscriptionCoordinator coordinator) {
        this(coordinator::transcribe, coordinator);
    }

    // Test seam: avoids actual FFmpeg, HTTP requests and billing in unit tests.
    SubtitlePlaybackSession(Function<Path, CompletableFuture<SubtitleTranscript>> request,
                            AutoCloseable resource) {
        this.request = Objects.requireNonNull(request, "request");
        this.resource = Objects.requireNonNull(resource, "resource");
    }

    /** Replaces the playing media; null clears the session after Stop. */
    public void load(Path media) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            generation++;
            if (pending != null) {
                pending.cancel(false);
                pending = null;
            }
            this.media = media;
            timeline = SubtitleTimeline.empty();
            ready = false;
            lastFailure = null;
            requestState = RequestState.IDLE;
            startIfNeeded();
        }
    }

    /** Can be used by the future GUI/CLI toggle without restarting playback. */
    public void setEnabled(boolean enabled) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            boolean wasEnabled = this.enabled;
            this.enabled = enabled;
            // A disabled-to-enabled transition may retry a previously failed request.
            if (enabled && !wasEnabled) {
                startIfNeeded();
            }
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public SubtitleTimeline timeline() {
        return timeline;
    }

    public Throwable lastFailure() {
        return lastFailure;
    }

    /** Distinguishes an in-progress request, an empty result and an actual failure. */
    public RequestState requestState() {
        return requestState;
    }

    // Must be called under lock. Never blocks: expensive extraction starts elsewhere.
    private void startIfNeeded() {
        if (!enabled || media == null || pending != null || ready || closed) {
            return;
        }
        long requestedGeneration = generation;
        requestState = RequestState.LOADING;
        lastFailure = null;
        CompletableFuture<SubtitleTranscript> future;
        try {
            future = Objects.requireNonNull(request.apply(media), "transcription future");
        } catch (RuntimeException failure) {
            lastFailure = failure;
            requestState = RequestState.FAILED;
            return;
        }
        pending = future;
        future.whenComplete((transcript, failure) -> {
            SubtitleTimeline successfulTimeline;
            synchronized (lock) {
                if (closed || generation != requestedGeneration) {
                    return;
                }
                pending = null;
                if (failure != null) {
                    lastFailure = failure;
                    requestState = RequestState.FAILED;
                    return;
                }
                try {
                    successfulTimeline = SubtitleTimeline.fromTranscript(transcript);
                    timeline = successfulTimeline;
                    lastFailure = null;
                    ready = true;
                    requestState = RequestState.READY;
                } catch (RuntimeException invalidResponse) {
                    lastFailure = invalidResponse;
                    requestState = RequestState.FAILED;
                    return;
                }
            }
            // Diagnostic I/O is opt-in and never runs while holding the session lock.
            SubtitleSrtExporter.exportIfRequested(transcript, successfulTimeline);
        });
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            closed = true;
            generation++;
            enabled = false;
            media = null;
            timeline = SubtitleTimeline.empty();
            requestState = RequestState.IDLE;
            if (pending != null) {
                pending.cancel(false);
                pending = null;
            }
        }
        try {
            resource.close();
        } catch (Exception failure) {
            // Shutdown must not fail video-player cleanup because of subtitles.
            System.err.println("Subtitle resource cleanup failed: " + failure.getMessage());
        }
    }
}
