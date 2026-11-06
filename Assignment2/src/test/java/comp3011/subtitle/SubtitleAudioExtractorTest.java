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
import java.util.stream.Stream;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.jupiter.api.Test;
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
        } finally {
            Files.deleteIfExists(result);
        }
        assertEquals(originalSize, Files.size(original), "Extraction must not alter the original media");
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
