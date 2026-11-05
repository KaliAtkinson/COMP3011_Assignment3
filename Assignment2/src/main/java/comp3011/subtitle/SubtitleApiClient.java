package comp3011.subtitle;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Non-blocking HTTP transport for Assignment 1's timestamped subtitle API.
 * This class uploads a WAV audio file, not an MP4 video file; extracting the
 * original media audio is deliberately handled in a separate component.
 *
 * The returned future must be handled off the JavaFX application thread or
 * delivered back to it with Platform.runLater when UI state is updated.
 * No OpenAI key is held in the player: only the local server calls OpenAI.
 *
 * AI Assistance: Developed with advice and code assistance from OpenAI ChatGPT.
 */
public final class SubtitleApiClient {
    public static final URI DEFAULT_ENDPOINT = URI.create("http://localhost:8080/api/v1/subtitles");

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(4);
    private final URI endpoint;
    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public SubtitleApiClient() {
        this(DEFAULT_ENDPOINT);
    }

    public SubtitleApiClient(URI endpoint) {
        this(endpoint, HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), new ObjectMapper());
    }

    // Package-private for deterministic transport tests and future dependency injection.
    SubtitleApiClient(URI endpoint, HttpClient httpClient, ObjectMapper mapper) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        if (!"http".equalsIgnoreCase(endpoint.getScheme()) && !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("subtitle endpoint must use HTTP(S)");
        }
    }

    /**
     * Uploads original audio extracted from the playing media as a WAV file.
     * HTTP processing and JSON decoding complete asynchronously.
     * A failed request completes the future exceptionally; it must not stop video playback.
     */
    public CompletableFuture<SubtitleTranscript> transcribe(Path wavFile) {
        Objects.requireNonNull(wavFile, "wavFile");
        try {
            if (!Files.isRegularFile(wavFile) || Files.size(wavFile) == 0) {
                return CompletableFuture.failedFuture(new IOException("Audio file is missing or empty"));
            }

            // Let HttpClient stream the file instead of copying its bytes into memory.
            String boundary = "comp3011-" + UUID.randomUUID();
            byte[] before = ("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"audio\"; filename=\"audio.wav\"\r\n"
                    + "Content-Type: audio/wav\r\n\r\n").getBytes(StandardCharsets.US_ASCII);
            byte[] after = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII);
            HttpRequest.BodyPublisher multipart = HttpRequest.BodyPublishers.concat(
                    HttpRequest.BodyPublishers.ofByteArray(before),
                    HttpRequest.BodyPublishers.ofFile(wavFile),
                    HttpRequest.BodyPublishers.ofByteArray(after));

            HttpRequest request = HttpRequest.newBuilder(endpoint)
                    .timeout(REQUEST_TIMEOUT)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(multipart)
                    .build();

            return httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                    .thenApply(response -> {
                        if (response.statusCode() != 200) {
                            // Avoid logging server errors containing potentially private transcription data.
                            throw new IllegalStateException("Subtitle server returned HTTP " + response.statusCode());
                        }
                        try {
                            return mapper.readValue(response.body(), SubtitleTranscript.class);
                        } catch (JsonProcessingException exception) {
                            throw new UncheckedIOException("Invalid subtitle server response", exception);
                        }
                    });
        } catch (IOException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }
}
