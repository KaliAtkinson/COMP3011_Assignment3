package comp3011.subtitle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;
import org.bytedeco.javacv.FrameRecorder;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_PCM_S16LE;
import static org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_S16;

/**
 * Extracts the media's original audio track into a temporary, Whisper-compatible WAV file.
 *
 * <p>This operation is blocking and must run on a background thread, never the JavaFX
 * application thread. A separate FFmpeg grabber is used so that transcription does not
 * seek or otherwise interfere with the live video's decoder.</p>
 *
 * <p>The caller owns the returned temporary file and must delete it <em>after</em> its
 * asynchronous upload has completed (successfully or otherwise). Failed or interrupted
 * extractions delete their own partial output.</p>
 *
 * AI Assistance: Developed with advice and code assistance from OpenAI ChatGPT.
 */
public final class SubtitleAudioExtractor {
    public static final int SAMPLE_RATE = 16_000;
    public static final int CHANNELS = 1;

    // Leave space below the transcription API's 25 MB upload limit for multipart overhead.
    private static final long MAX_WAV_BYTES = 24_000_000L;
    private static final int SIZE_CHECK_INTERVAL = 32;

    private final Path temporaryDirectory;

    public SubtitleAudioExtractor() {
        this(Path.of(System.getProperty("java.io.tmpdir")));
    }

    // Allow deterministic cleanup tests without writing to the user's regular temp folder.
    SubtitleAudioExtractor(Path temporaryDirectory) {
        this.temporaryDirectory = Objects.requireNonNull(temporaryDirectory, "temporaryDirectory");
    }

    /**
     * Creates a temporary PCM WAV containing only the audio from {@code mediaFile}.
     *
     * @return a nonempty 16 kHz, 16-bit, mono WAV; caller must delete it when finished
     * @throws IOException when the media has no audio, decoding fails, or output is too large
     */
    public Path extract(Path mediaFile) throws IOException {
        Objects.requireNonNull(mediaFile, "mediaFile");
        if (!Files.isRegularFile(mediaFile)) {
            throw new IOException("Media file does not exist: " + mediaFile);
        }

        Path temporaryWav = Files.createTempFile(temporaryDirectory, "comp3011-subtitles-", ".wav");
        boolean completed = false;
        try {
            extractTo(mediaFile, temporaryWav);
            if (Files.size(temporaryWav) <= 44) {
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
            // grabSamples() skips video decoding; this grabber is independent of playback.
            grabber.start();
            if (grabber.getAudioChannels() <= 0 || grabber.getSampleRate() <= 0) {
                throw new IOException("The media file has no usable audio track");
            }

            try (FFmpegFrameRecorder recorder = new FFmpegFrameRecorder(temporaryWav.toFile(), CHANNELS)) {
                recorder.setFormat("wav");
                recorder.setAudioCodec(AV_CODEC_ID_PCM_S16LE);
                recorder.setSampleFormat(AV_SAMPLE_FMT_S16);
                recorder.setSampleRate(SAMPLE_RATE);
                recorder.setAudioChannels(CHANNELS);
                recorder.setAudioBitrate(SAMPLE_RATE * Short.BYTES * 8);
                recorder.start();

                int processed = 0;
                int sampleFramesRecorded = 0;
                Frame frame;
                while ((frame = grabber.grabSamples()) != null) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new IOException("Audio extraction was interrupted");
                    }
                    if (frame.samples != null && frame.samples.length > 0) {
                        int sampleRate = frame.sampleRate > 0 ? frame.sampleRate : grabber.getSampleRate();
                        int channels = frame.audioChannels > 0 ? frame.audioChannels : grabber.getAudioChannels();
                        recorder.recordSamples(sampleRate, channels, frame.samples);
                        sampleFramesRecorded++;
                    }
                    if (++processed % SIZE_CHECK_INTERVAL == 0) {
                        verifySize(temporaryWav);
                    }
                }
                if (sampleFramesRecorded == 0) {
                    throw new IOException("No audio samples were extracted from the media");
                }
                // AutoCloseable finalises the WAV header and releases the recorder.
                // Do not call stop() explicitly: FrameRecorder.close() already does so.
            }
            verifySize(temporaryWav);
        } catch (FrameGrabber.Exception | FrameRecorder.Exception e) {
            throw new IOException("Unable to extract media audio", e);
        }
    }

    private static void verifySize(Path wavFile) throws IOException {
        if (Files.size(wavFile) > MAX_WAV_BYTES) {
            throw new IOException("Audio is too long for a single Whisper upload (24 MB WAV limit)");
        }
    }
}
