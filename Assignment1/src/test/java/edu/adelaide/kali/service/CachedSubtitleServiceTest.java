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
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
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
    void excerptOfPreviouslyTranscribedMovieDoesNotBillAgainAndRebasesWords() {
        short[] movie = waveform(65, 1);
        int startSample = 21 * 16_000 + 5_431; // not aligned to a WAV energy window
        int clipSamples = 25 * 16_000;
        short[] excerptSamples = Arrays.copyOfRange(movie, startSample, startSample + clipSamples);
        // Simulate slightly different decoded samples from AAC re-encoding.
        for (int i = 0; i < excerptSamples.length; i++) {
            excerptSamples[i] = (short) (excerptSamples[i] * 0.96);
        }
        SubtitleResponse movieTranscript = new SubtitleResponse("Before. In the clip. After.", 65,
                List.of(new SubtitleResponse.Segment(5, 8, "Before.", 0.1, -0.3),
                        new SubtitleResponse.Segment(22, 25, "In the clip.", 0.1, -0.3),
                        new SubtitleResponse.Segment(55, 58, "After.", 0.1, -0.3)),
                List.of(new SubtitleResponse.Word(5, 6, "Before."),
                        new SubtitleResponse.Word(22, 22.5, "In"),
                        new SubtitleResponse.Word(23, 23.5, "the"),
                        new SubtitleResponse.Word(24, 24.5, "clip."),
                        new SubtitleResponse.Word(56, 57, "After.")));
        AtomicInteger calls = new AtomicInteger();
        var service = new CachedSubtitleService(file -> {
            calls.incrementAndGet();
            return movieTranscript;
        });
        service.transcribe(wav("movie.wav", movie));
        SubtitleResponse clip = service.transcribe(wav("clip.wav", excerptSamples));
        assertEquals(1, calls.get(), "a matching audio fragment must not call Whisper twice");
        assertEquals(25, clip.duration(), 0.001);
        assertEquals(1, clip.segments().size());
        assertEquals("In the clip.", clip.segments().getFirst().text());
        assertEquals(3, clip.words().size());
        assertEquals(22 - startSample / 16_000.0, clip.words().getFirst().start(), 0.075);
        assertEquals("In the clip.", clip.text());
        // Subsequent requests for the excerpt itself are still exact cache hits.
        assertEquals(clip, service.transcribe(wav("renamed-clip.wav", excerptSamples)));
        assertEquals(1, calls.get());
    }

    @Test
    void excerptMatchingSupportsWavWithAdditionalRiffMetadata() throws IOException {
        short[] movie = waveform(40, 3);
        short[] clip = Arrays.copyOfRange(movie, 10 * 16_000, 25 * 16_000);
        byte[] rawMovie = wav("original.wav", movie).getBytes();
        // Insert an ordinary LIST chunk between the fmt and data chunks.
        ByteBuffer withMetadata = ByteBuffer.allocate(rawMovie.length + 12)
                .order(ByteOrder.LITTLE_ENDIAN);
        withMetadata.put(rawMovie, 0, 36);
        withMetadata.put("LIST".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        withMetadata.putInt(4);
        withMetadata.put("TEST".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        withMetadata.put(rawMovie, 36, rawMovie.length - 36);
        withMetadata.putInt(4, withMetadata.capacity() - 8); // RIFF length
        AtomicInteger calls = new AtomicInteger();
        var service = new CachedSubtitleService(file -> {
            calls.incrementAndGet();
            return new SubtitleResponse("test", 40,
                    List.of(new SubtitleResponse.Segment(11, 12, "test", 0, -0.2)),
                    List.of(new SubtitleResponse.Word(11, 12, "test")));
        });
        service.transcribe(new MockMultipartFile("audio", "original.wav", "audio/wav", withMetadata.array()));
        SubtitleResponse result = service.transcribe(wav("clip.wav", clip));
        assertEquals(1, calls.get());
        assertEquals(1, result.words().size());
        assertEquals(1.0, result.words().getFirst().start(), 0.075);
    }

    @Test
    void differentRecordingsCannotBorrowAnotherRecordingSubtitleExcerpt() {
        AtomicInteger calls = new AtomicInteger();
        var service = new CachedSubtitleService(file -> {
            calls.incrementAndGet();
            return RESULT;
        });
        service.transcribe(wav("original.wav", waveform(54, 1)));
        service.transcribe(wav("unrelated.wav", waveform(25, 8)));
        assertEquals(2, calls.get());
    }

    @Test
    void silenceIsNotSufficientEvidenceOfAnExcerptMatch() {
        AtomicInteger calls = new AtomicInteger();
        var service = new CachedSubtitleService(file -> {
            calls.incrementAndGet();
            return RESULT;
        });
        service.transcribe(wav("silent-original.wav", new short[40 * 16_000]));
        service.transcribe(wav("silent-excerpt.wav", new short[20 * 16_000]));
        assertEquals(2, calls.get());
    }

    private static short[] waveform(int seconds, int seed) {
        short[] samples = new short[seconds * 16_000];
        for (int i = 0; i < samples.length; i++) {
            double t = i / 16_000.0;
            double envelope = 0.11 + 0.18 * Math.pow(Math.sin(t * (0.19 + seed * 0.073)), 2)
                    + 0.15 * Math.pow(Math.cos(t * (0.6 + seed * 0.13)), 2);
            double carrier = Math.sin(t * (1400 + seed * 23) + Math.sin(t * 1.1) * 2);
            samples[i] = (short) (carrier * envelope * Short.MAX_VALUE);
        }
        return samples;
    }

    private static MockMultipartFile wav(String name, short[] samples) {
        ByteBuffer buffer = ByteBuffer.allocate(44 + samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.put("RIFF".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(36 + samples.length * 2);
        buffer.put("WAVEfmt ".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(16).putShort((short) 1).putShort((short) 1);
        buffer.putInt(16_000).putInt(32_000).putShort((short) 2).putShort((short) 16);
        buffer.put("data".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        buffer.putInt(samples.length * 2);
        for (short sample : samples) buffer.putShort(sample);
        return new MockMultipartFile("audio", name, "audio/wav", buffer.array());
    }

    @Test
    void springPrefersCacheWrapperToDirectWhisperService() {
        assertTrue(CachedSubtitleService.class.isAnnotationPresent(Primary.class));
    }
}
