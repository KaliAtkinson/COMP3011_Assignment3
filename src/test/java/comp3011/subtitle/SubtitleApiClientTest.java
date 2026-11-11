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

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SubtitleApiClientTest {
    private HttpServer server;
    private URI endpoint;
    private Path audio;

    @BeforeEach
    void startServer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v1/subtitles");
        audio = Files.createTempFile("subtitle-upload-", ".wav");
        Files.write(audio, new byte[] {0, 1, 10, 13, 37, (byte) 255});
    }

    @AfterEach
    void stopServer() throws Exception {
        if (server != null) {
            server.stop(0);
        }
        Files.deleteIfExists(audio);
    }

    @Test
    void postsOriginalAudioInMultipartFieldAndReadsTimedResponse() throws Exception {
        AtomicReference<String> contentType = new AtomicReference<>();
        AtomicReference<byte[]> requestBody = new AtomicReference<>();
        server.createContext("/api/v1/subtitles", exchange -> {
            contentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            requestBody.set(exchange.getRequestBody().readAllBytes());
            byte[] response = ("{\"text\":\"Hello there\",\"duration\":2.5,"
                    + "\"segments\":[{\"start\":0.5,\"end\":2.0,\"text\":\"Hello there\","
                    + "\"noSpeechProb\":0.1,\"avgLogProb\":-0.2}],"
                    + "\"words\":[{\"start\":0.5,\"end\":0.9,\"word\":\"Hello\"},"
                    + "{\"start\":1.0,\"end\":1.5,\"word\":\"there\"}]}"
            ).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var out = exchange.getResponseBody()) {
                out.write(response);
            }
        });
        server.start();

        SubtitleTranscript transcript = new SubtitleApiClient(endpoint).transcribe(audio).get(5, TimeUnit.SECONDS);

        assertEquals("Hello there", transcript.text());
        assertEquals(2.5, transcript.duration());
        assertEquals(1, transcript.segments().size());
        assertEquals("Hello there", transcript.segments().getFirst().text());
        assertEquals(2, transcript.words().size());
        assertEquals("Hello", transcript.words().getFirst().word());
        assertTrue(contentType.get().startsWith("multipart/form-data; boundary="));
        String body = new String(requestBody.get(), StandardCharsets.ISO_8859_1);
        assertTrue(body.contains("name=\"audio\"; filename=\"audio.wav\""));
        assertTrue(body.contains("Content-Type: audio/wav"));
        // Confirm the payload is the original binary file, rather than decoded/transformed audio.
        byte[] expected = Files.readAllBytes(audio);
        assertTrue(indexOf(requestBody.get(), expected) >= 0);
    }

    @Test
    void serverFailureCompletesFutureExceptionallyWithoutLeakingResponseBody() throws Exception {
        server.createContext("/api/v1/subtitles", exchange -> {
            byte[] body = "private audio/transcription error content".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, body.length);
            try (var out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();

        CompletionException failure = assertThrows(CompletionException.class,
                () -> new SubtitleApiClient(endpoint).transcribe(audio).join());
        assertTrue(failure.getCause().getMessage().contains("503"));
        assertFalse(failure.getCause().getMessage().contains("private"));
    }

    @Test
    void missingOrEmptyAudioDoesNotAttemptUpload() throws Exception {
        server.start();
        Files.delete(audio);
        CompletionException missing = assertThrows(CompletionException.class,
                () -> new SubtitleApiClient(endpoint).transcribe(audio).join());
        assertTrue(missing.getCause() instanceof IOException);
        Files.write(audio, new byte[0]);
        CompletionException empty = assertThrows(CompletionException.class,
                () -> new SubtitleApiClient(endpoint).transcribe(audio).join());
        assertTrue(empty.getCause() instanceof IOException);
    }

    @Test
    void transcriptIsImmutableAndRejectsInvalidTimeRanges() {
        SubtitleTranscript transcript = new SubtitleTranscript("", 3.0,
                List.of(new SubtitleTranscript.Segment(1.0, 2.0, "A", 0.0, -0.2)),
                List.of(new SubtitleTranscript.Word(1.0, 1.5, "A")));
        assertThrows(UnsupportedOperationException.class, () -> transcript.segments().clear());
        assertThrows(IllegalArgumentException.class,
                () -> new SubtitleTranscript.Word(2.0, 1.0, "backwards"));
        assertThrows(IllegalArgumentException.class,
                () -> new SubtitleTranscript("", Double.NaN, List.of(), List.of()));
    }

    private static int indexOf(byte[] haystack, byte[] needle) {
        outer: for (int i = 0; i <= haystack.length - needle.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }
}
