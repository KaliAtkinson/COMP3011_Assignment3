package edu.adelaide.kali.service;

import edu.adelaide.kali.dto.SubtitleResponse;
import edu.adelaide.kali.exception.TranscriptionException;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Reuses successful Whisper results for identical uploaded audio. The cache
 * contains transcripts, not audio, and is bounded to 16 recent recordings.
 * In-flight requests are shared so concurrent clients cannot bill the same
 * recording multiple times simply by arriving together.
 *
 * Only the real Whisper profile is cached; the local mock remains unchanged.
 * Cache entries are in memory and disappear when the server restarts.
 *
 * AI Assistance: Developed with guidance and code assistance from OpenAI ChatGPT.
 */
@Service
@Primary
@Profile("!local")
public final class CachedSubtitleService implements SubtitleService {
    private static final int MAX_ENTRIES = 16;
    private final SubtitleService whisper;
    private final Object lock = new Object();
    // Access ordering evicts the least recently *used* completed transcript.
    private final LinkedHashMap<String, SubtitleResponse> completed =
            new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, CompletableFuture<SubtitleResponse>> inFlight =
            new java.util.HashMap<>();

    public CachedSubtitleService(@Qualifier("whisperSubtitleService") SubtitleService whisper) {
        this.whisper = whisper;
    }

    @Override
    public SubtitleResponse transcribe(MultipartFile audioFile) {
        if (audioFile == null || audioFile.isEmpty()) {
            throw new IllegalArgumentException("Audio file must not be empty");
        }

        String key = sha256(audioFile);
        CompletableFuture<SubtitleResponse> pending;
        boolean owner = false;
        synchronized (lock) {
            SubtitleResponse existing = completed.get(key);
            if (existing != null) {
                return existing;
            }
            pending = inFlight.get(key);
            if (pending == null) {
                pending = new CompletableFuture<>();
                inFlight.put(key, pending);
                owner = true;
            }
        }

        if (owner) {
            try {
                // Never hold the cache lock across an outbound network request.
                SubtitleResponse result = java.util.Objects.requireNonNull(
                        whisper.transcribe(audioFile), "Whisper response must not be null");
                synchronized (lock) {
                    completed.put(key, result);
                    if (completed.size() > MAX_ENTRIES) {
                        Iterator<String> keys = completed.keySet().iterator();
                        keys.next();
                        keys.remove();
                    }
                }
                pending.complete(result);
                return result;
            } catch (RuntimeException | Error failure) {
                // Failed or rejected requests must be retried, not cached.
                pending.completeExceptionally(failure);
                throw failure;
            } finally {
                synchronized (lock) {
                    inFlight.remove(key, pending);
                }
            }
        }

        try {
            return pending.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new TranscriptionException("Interrupted while waiting for subtitles");
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new TranscriptionException("Unable to retrieve subtitles from Whisper");
        }
    }

    private static String sha256(MultipartFile audioFile) {
        try {
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            // Stream rather than buffering an entire audio upload in the cache.
            try (InputStream source = audioFile.getInputStream()) {
                byte[] buffer = new byte[64 * 1024];
                int bytesRead;
                while ((bytesRead = source.read(buffer)) != -1) {
                    hash.update(buffer, 0, bytesRead);
                }
            }
            return java.util.HexFormat.of().formatHex(hash.digest());
        } catch (IOException failure) {
            throw new TranscriptionException("Unable to read audio for subtitle caching");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }
}
