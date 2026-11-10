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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.ShortBuffer;
import java.util.stream.Stream;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.junit.jupiter.api.Test;
import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_AAC;
import org.junit.jupiter.api.io.TempDir;

class SubtitleAudioExtractorTest {
    @TempDir
    Path directory;

    @Test
    void extractsSixteenKilohertzMonoWavFromStereoAudio() throws Exception {
        Path original = directory.resolve("original.wav");
        writeStereoPcmAudio(original);
        long originalSize = Files.size(original);
        SubtitleAudioExtractor extractor = new SubtitleAudioExtractor(directory);

        Path result = extractor.extract(original);
        try (AudioInputStream audio = AudioSystem.getAudioInputStream(result.toFile())) {
            AudioFormat format = audio.getFormat();
            assertEquals(AudioFormat.Encoding.PCM_SIGNED, format.getEncoding());
            assertEquals(SubtitleAudioExtractor.SAMPLE_RATE, format.getSampleRate(), 1.0);
            assertEquals(SubtitleAudioExtractor.CHANNELS, format.getChannels());
            assertEquals(16, format.getSampleSizeInBits());
            assertFalse(format.isBigEndian());

            byte[] samples = audio.readAllBytes();
            // Half a second of audio survives resampling, and is not silent or empty.
            assertTrue(samples.length >= 14_000 && samples.length <= 18_000);
            assertTrue(containsNonzero(samples));
            assertToneSurvives(samples, 440, 12_000, 16_000);
        } finally {
            Files.deleteIfExists(result);
        }
        assertEquals(originalSize, Files.size(original), "Extraction must not alter the original media");
    }

    @Test
    void preservesRecognisableSamplesFromAacAudio() throws Exception {
        // The supplied trailer uses AAC, unlike the original PCM-only smoke test.
        // A valid WAV header and nonzero amplitude alone cannot detect loud corruption.
        Path compressed = directory.resolve("source.m4a");
        writeAacStereoAudio(compressed);

        Path output = new SubtitleAudioExtractor(directory).extract(compressed);
        try (AudioInputStream audio = AudioSystem.getAudioInputStream(output.toFile())) {
            AudioFormat format = audio.getFormat();
            assertEquals(16_000, format.getSampleRate(), 1.0);
            assertEquals(1, format.getChannels());
            assertToneSurvives(audio.readAllBytes(), 440, 12_000, 16_000);
        } finally {
            Files.deleteIfExists(output);
        }
    }

    @Test
    void missingInputDoesNotCreateTemporaryFile() throws Exception {
        SubtitleAudioExtractor extractor = new SubtitleAudioExtractor(directory);
        assertThrows(IOException.class, () -> extractor.extract(directory.resolve("missing.mp4")));
        assertEquals(0, countTemporaryWavs());
    }

    @Test
    void invalidMediaDeletesItsPartialOutput() throws Exception {
        Path invalid = directory.resolve("invalid.mp4");
        Files.writeString(invalid, "not a media file");
        SubtitleAudioExtractor extractor = new SubtitleAudioExtractor(directory);

        assertThrows(IOException.class, () -> extractor.extract(invalid));
        assertEquals(0, countTemporaryWavs());
    }

    @Test
    void interruptedExtractionDoesNotLeaveTemporaryAudio() throws Exception {
        Path original = directory.resolve("interrupt.wav");
        writeStereoPcmAudio(original);
        SubtitleAudioExtractor extractor = new SubtitleAudioExtractor(directory);

        Thread.currentThread().interrupt();
        try {
            assertThrows(IOException.class, () -> extractor.extract(original));
        } finally {
            Thread.interrupted(); // Restore JUnit's test thread for subsequent tests.
        }
        assertEquals(0, countTemporaryWavs());
    }

    private long countTemporaryWavs() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().startsWith("comp3011-subtitles-")).count();
        }
    }

    private static boolean containsNonzero(byte[] bytes) {
        for (byte value : bytes) {
            if (value != 0) {
                return true;
            }
        }
        return false;
    }

    /**
     * Measures the actual frequency content, not merely whether there are nonzero bytes.
     * A distorted or byte-swapped recording typically fails the tone-purity test.
     */
    private static void assertToneSurvives(byte[] pcm, int frequency, int originalAmplitude, int rate) {
        int frames = pcm.length / 2;
        assertTrue(frames > rate / 4, "Expected a meaningful audio recording");

        // Skip conversion startup; use a stable window of the decoded signal.
        int begin = rate / 10;
        int end = Math.min(frames, begin + rate / 5);
        double sin = 0;
        double cos = 0;
        double squares = 0;
        for (int frame = begin; frame < end; frame++) {
            int offset = frame * 2;
            int sample = (short) ((pcm[offset] & 0xff) | ((pcm[offset + 1] & 0xff) << 8));
            double phase = 2.0 * Math.PI * frequency * frame / rate;
            sin += sample * Math.sin(phase);
            cos += sample * Math.cos(phase);
            squares += (double) sample * sample;
        }
        int n = end - begin;
        double measuredAmplitude = 2.0 * Math.hypot(sin, cos) / n;
        double toneEnergyFraction = (measuredAmplitude * measuredAmplitude / 2.0) / (squares / n);
        assertTrue(measuredAmplitude > originalAmplitude * 0.65
                        && measuredAmplitude < originalAmplitude * 1.30,
                "Extracted audio has incorrect amplitude: " + measuredAmplitude);
        assertTrue(toneEnergyFraction > 0.85,
                "Extracted audio is distorted: 440 Hz tone explains only " + toneEnergyFraction + " of energy");
    }

    private static void writeAacStereoAudio(Path output) throws Exception {
        int rate = 44_100;
        short[] interleaved = new short[rate * 2];
        for (int frame = 0; frame < rate; frame++) {
            short value = (short) Math.round(Math.sin(2 * Math.PI * 440 * frame / rate) * 12_000);
            interleaved[frame * 2] = value;
            interleaved[frame * 2 + 1] = value;
        }
        try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(output.toFile(), 2)) {
            recorder.setFormat("ipod");
            recorder.setAudioCodec(AV_CODEC_ID_AAC);
            recorder.setSampleRate(rate);
            recorder.setAudioChannels(2);
            recorder.setAudioBitrate(128_000);
            recorder.start();
            recorder.recordSamples(rate, 2, ShortBuffer.wrap(interleaved));
        }
    }

    private static void writeStereoPcmAudio(Path output) throws Exception {
        int sampleRate = 44_100;
        int frames = sampleRate / 2;
        byte[] pcm = new byte[frames * 2 * Short.BYTES];
        for (int frame = 0; frame < frames; frame++) {
            short sample = (short) (Math.sin(2 * Math.PI * 440 * frame / sampleRate) * 12_000);
            int position = frame * 4;
            pcm[position] = (byte) sample;
            pcm[position + 1] = (byte) (sample >>> 8);
            pcm[position + 2] = (byte) sample;
            pcm[position + 3] = (byte) (sample >>> 8);
        }
        AudioFormat format = new AudioFormat(sampleRate, 16, 2, true, false);
        try (AudioInputStream input = new AudioInputStream(
                new ByteArrayInputStream(pcm), format, frames)) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, output.toFile());
        }
    }
}
