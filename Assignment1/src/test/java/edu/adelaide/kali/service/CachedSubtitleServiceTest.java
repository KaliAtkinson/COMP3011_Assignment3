package edu.adelaide.kali.service;

import edu.adelaide.kali.config.AudioUsageTracker;
import edu.adelaide.kali.config.OpenAiProperties;
import edu.adelaide.kali.dto.SubtitleResponse;
import edu.adelaide.kali.exception.TranscriptionException;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/*
 * AI Assistance:
 * This test suite was developed and refined with advice, guidance,
 * and code assistance from OpenAI ChatGPT.
 * Test implementations were reviewed and validated as part of
 * the development process.
 */
class CachedSubtitleServiceTest {
    private static final SubtitleResponse RESULT = new SubtitleResponse(
            "hello", 2.0, List.of(new SubtitleResponse.Segment(0, 2, "hello", 0, -0.1)),
            List.of(new SubtitleResponse.Word(0, 1, "hello")));

    private static MockMultipartFile audio(String name, String bytes) {
        return new MockMultipartFile("audio", name, "audio/wav", bytes.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void sameAudioIsTranscribedOnceEvenIfFilenameChanges() {
        AtomicInteger calls = new AtomicInteger();
        var service = new CachedSubtitleService(file -> { calls.incrementAndGet(); return RESULT; });
        assertSame(RESULT, service.transcribe(audio("first.wav", "audio-1")));
        assertSame(RESULT, service.transcribe(audio("other.wav", "audio-1")));
        assertEquals(1, calls.get());
    }

    @Test
    void differentAudioDoesNotShareCacheEntries() {
        AtomicInteger calls = new AtomicInteger();
        var service = new CachedSubtitleService(file -> { calls.incrementAndGet(); return RESULT; });
        service.transcribe(audio("sample.wav", "audio-A"));
        service.transcribe(audio("sample.wav", "audio-B"));
        assertEquals(2, calls.get());
    }

    @Test
    void failedCallsAreNotCachedAndMayBeRetried() {
        AtomicInteger calls = new AtomicInteger();
        var service = new CachedSubtitleService(file -> {
            if (calls.incrementAndGet() == 1) throw new TranscriptionException("rate limited");
            return RESULT;
        });
        assertThrows(TranscriptionException.class, () -> service.transcribe(audio("a.wav", "a")));
        assertSame(RESULT, service.transcribe(audio("a.wav", "a")));
        assertSame(RESULT, service.transcribe(audio("a.wav", "a")));
        assertEquals(2, calls.get());
    }

    @Test
    void simultaneousIdenticalUploadsShareTheSameWhisperCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch insideService = new CountDownLatch(1);
        CountDownLatch releaseService = new CountDownLatch(1);
        var service = new CachedSubtitleService(file -> {
            calls.incrementAndGet();
            insideService.countDown();
            try {
                if (!releaseService.await(5, TimeUnit.SECONDS)) throw new AssertionError("test timed out");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new TranscriptionException("interrupted");
            }
            return RESULT;
        });
        try (var executor = Executors.newFixedThreadPool(6)) {
            var first = executor.submit(() -> service.transcribe(audio("one.wav", "same")));
            assertTrue(insideService.await(5, TimeUnit.SECONDS));
            var second = executor.submit(() -> service.transcribe(audio("two.wav", "same")));
            var third = executor.submit(() -> service.transcribe(audio("three.wav", "same")));
            releaseService.countDown();
            assertSame(RESULT, first.get(5, TimeUnit.SECONDS));
            assertSame(RESULT, second.get(5, TimeUnit.SECONDS));
            assertSame(RESULT, third.get(5, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
        } finally {
            releaseService.countDown();
        }
    }

    @Test
    void evictsOldestCompletedResultAfterSixteenFiles() {
        AtomicInteger calls = new AtomicInteger();
        var service = new CachedSubtitleService(file -> { calls.incrementAndGet(); return RESULT; });
        for (int i = 0; i < 16; i++) service.transcribe(audio("a.wav", "audio-" + i));
        service.transcribe(audio("a.wav", "audio-0")); // mark first as recently used
        service.transcribe(audio("a.wav", "audio-16")); // evicts audio-1
        service.transcribe(audio("a.wav", "audio-0")); // still cached
        assertEquals(17, calls.get());
        service.transcribe(audio("a.wav", "audio-1"));
        assertEquals(18, calls.get());
    }

    @Test
    void unreadableAudioIsRejectedWithoutCallingWhisper() {
        AtomicInteger calls = new AtomicInteger();
        var service = new CachedSubtitleService(file -> { calls.incrementAndGet(); return RESULT; });
        var broken = new MockMultipartFile("audio", "broken.wav", "audio/wav", new byte[]{1}) {
            @Override public InputStream getInputStream() throws IOException { throw new IOException("private path"); }
        };
        var error = assertThrows(TranscriptionException.class, () -> service.transcribe(broken));
        assertFalse(error.getMessage().contains("private path"));
        assertEquals(0, calls.get());
    }

    @Test
    void rejectsEmptyAudio() {
        var service = new CachedSubtitleService(file -> RESULT);
        assertThrows(IllegalArgumentException.class, () -> service.transcribe(audio("empty.wav", "")));
    }

    @Test
    void integratesWithWhisperUsageCounterAndMakesOnlyOneHttpCall() {
        var builder = RestClient.builder();
        var remote = MockRestServiceServer.bindTo(builder).build();
        var usage = new AudioUsageTracker();
        var whisper = new WhisperSubtitleService(builder.build(),
                new OpenAiProperties("https://api.openai.com/v1/audio/transcriptions", "unused", "test-key"), usage);
        var cache = new CachedSubtitleService(whisper);
        remote.expect(requestTo("https://api.openai.com/v1/audio/transcriptions"))
                .andRespond(withSuccess("""
                        {"text":"hello","duration":2.0,
                         "segments":[{"start":0,"end":2,"text":"hello","no_speech_prob":0,"avg_logprob":-0.1}],
                         "words":[{"start":0,"end":1,"word":"hello"}],
                         "usage":{"seconds":2.5}}
                        """, MediaType.APPLICATION_JSON));
        cache.transcribe(audio("one.wav", "same"));
        cache.transcribe(audio("two.wav", "same"));
        assertEquals(2.5, usage.getSeconds());
        remote.verify();
    }

    @Test
    void springPrefersCacheWrapperToDirectWhisperService() {
        assertTrue(CachedSubtitleService.class.isAnnotationPresent(Primary.class));
    }
}
