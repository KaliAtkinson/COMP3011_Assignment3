package comp3011.subtitle;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ShortBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

/**
 * Extracts the original media's audio to a temporary 16 kHz, mono, 16-bit PCM WAV.
 *
 * <p>The grabber decodes and resamples the audio. Decoded shorts are written directly
 * as little-endian WAV samples, rather than passing them into a second FFmpeg recorder.
 * This avoids a second conversion stage that produced an unintelligible recording
 * with some AAC inputs. The media decoder used for live playback is untouched.</p>
 *
 * <p>Run only on a worker thread. The caller owns the returned temporary WAV and
 * must delete it after the asynchronous HTTP upload finishes.</p>
 *
 * <p>AI Assistance: Developed and refined with advice and code assistance from
 * OpenAI ChatGPT; reviewed as part of the development process.</p>
 */
public final class SubtitleAudioExtractor {
    public static final int SAMPLE_RATE = 16_000;
    public static final int CHANNELS = 1;

    private static final long MAX_WAV_BYTES = 24_000_000L;
    private static final int WAV_HEADER_BYTES = 44;
    private static final int CHUNK_SAMPLES = 4096;

    private final Path temporaryDirectory;

    public SubtitleAudioExtractor() {
        this(Path.of(System.getProperty("java.io.tmpdir")));
    }

    SubtitleAudioExtractor(Path temporaryDirectory) {
        this.temporaryDirectory = Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
    }

    public Path extract(Path mediaFile) throws IOException {
        Objects.requireNonNull(mediaFile, "mediaFile");
        if (!Files.isRegularFile(mediaFile)) {
            throw new IOException("Media file does not exist: " + mediaFile);
        }

        Path temporaryWav = Files.createTempFile(temporaryDirectory, "comp3011-subtitles-", ".wav");
        boolean completed = false;
        try {
            extractTo(mediaFile, temporaryWav);
            if (Files.size(temporaryWav) <= WAV_HEADER_BYTES) {
                throw new IOException("No audio samples were extracted from the media");
            }
            completed = true;
            return temporaryWav;
        } finally {
            if (!completed) {
                Files.deleteIfExists(temporaryWav);
            }
        }
    }

    private static void extractTo(Path mediaFile, Path temporaryWav) throws IOException {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(mediaFile.toFile())) {
            // Let FFmpeg's decoder/resampler perform the conversion ONCE.
            // In SHORT mode the grabber supplies signed 16-bit samples.
            grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
            grabber.setSampleRate(SAMPLE_RATE);
            grabber.setAudioChannels(CHANNELS);
            grabber.start();

            if (!grabber.hasAudio()) {
                throw new IOException("The media file has no usable audio track");
            }

            try (RandomAccessFile out = new RandomAccessFile(temporaryWav.toFile(), "rw")) {
                out.setLength(0);
                out.write(new byte[WAV_HEADER_BYTES]); // Header sizes are patched at EOF.

                long sampleCount = 0;
                byte[] chunk = new byte[CHUNK_SAMPLES * Short.BYTES];
                Frame frame;
                while ((frame = grabber.grabSamples()) != null) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Audio extraction was interrupted");
                    }
                    if (frame.samples == null || frame.samples.length == 0) {
                        continue;
                    }
                    if (frame.sampleRate != SAMPLE_RATE || frame.audioChannels != CHANNELS
                            || frame.samples.length != 1 || !(frame.samples[0] instanceof ShortBuffer)) {
                        throw new IOException("Decoder returned unexpected audio format instead of 16 kHz mono PCM");
                    }
                    ShortBuffer samples = ((ShortBuffer) frame.samples[0]).duplicate();
                    int remaining = samples.remaining();
                    if (remaining > (MAX_WAV_BYTES - WAV_HEADER_BYTES - sampleCount * Short.BYTES)
                            / Short.BYTES) {
                        throw new IOException("Audio is too long for a single Whisper upload (24 MB WAV limit)");
                    }
                    while (samples.hasRemaining()) {
                        int bytes = 0;
                        while (samples.hasRemaining() && bytes < chunk.length) {
                            short sample = samples.get();
                            chunk[bytes++] = (byte) sample;
                            chunk[bytes++] = (byte) (sample >>> 8);
                        }
                        out.write(chunk, 0, bytes);
                    }
                    sampleCount += remaining;
                }

                if (sampleCount == 0) {
                    throw new IOException("No audio samples were extracted from the media");
                }
                writeWaveHeader(out, sampleCount * Short.BYTES);
            }
        } catch (FrameGrabber.Exception e) {
            throw new IOException("Unable to decode original media audio", e);
        }
    }

    private static void writeWaveHeader(RandomAccessFile out, long dataBytes) throws IOException {
        if (dataBytes > Integer.MAX_VALUE - 36L) {
            throw new IOException("WAV data exceeds RIFF limit");
        }
        out.seek(0);
        out.writeBytes("RIFF");
        writeLittleEndian32(out, 36 + dataBytes);
        out.writeBytes("WAVE");
        out.writeBytes("fmt ");
        writeLittleEndian32(out, 16);                  // PCM format chunk length
        writeLittleEndian16(out, 1);                   // Uncompressed PCM
        writeLittleEndian16(out, CHANNELS);
        writeLittleEndian32(out, SAMPLE_RATE);
        writeLittleEndian32(out, SAMPLE_RATE * CHANNELS * Short.BYTES);
        writeLittleEndian16(out, CHANNELS * Short.BYTES);
        writeLittleEndian16(out, 16);                  // Bits per sample
        out.writeBytes("data");
        writeLittleEndian32(out, dataBytes);
    }

    private static void writeLittleEndian16(RandomAccessFile out, int value) throws IOException {
        out.write(value & 0xff);
        out.write((value >>> 8) & 0xff);
    }

    private static void writeLittleEndian32(RandomAccessFile out, long value) throws IOException {
        out.write((int) (value & 0xff));
        out.write((int) ((value >>> 8) & 0xff));
        out.write((int) ((value >>> 16) & 0xff));
        out.write((int) ((value >>> 24) & 0xff));
    }
}
