package edu.adelaide.kali.service;

import edu.adelaide.kali.dto.SubtitleResponse;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Matches a shorter, independently encoded audio excerpt against a cached
 * recording using a low-resolution energy envelope. Only compact per-window
 * measurements are retained; the audio samples themselves are not cached.
 *
 * <p>The video player's extractor emits 16 kHz, mono, 16-bit PCM WAV files.
 * Standard RIFF metadata chunks are supported. Unrecognised audio formats bypass
 * excerpt matching and retain the existing exact-hash/Whisper behaviour.</p>
 *
 * <p>AI Assistance: Developed with advice and code assistance from OpenAI ChatGPT.</p>
 */
final class WaveformExcerptMatcher {
    private static final int RIFF_HEADER_BYTES = 12;
    private static final int SAMPLE_RATE = 16_000;
    private static final int SAMPLES_PER_STEP = 800; // 50 ms
    private static final double STEP_SECONDS = 0.05;
    private static final int EDGE_STEPS = 4; // Ignore 200 ms of codec boundaries.
    private static final int MIN_MATCH_STEPS = 160; // Eight seconds of useful signal.
    private static final double MIN_VARIANCE = 0.001;
    private static final double MIN_CORRELATION = 0.985;
    private static final long MAX_DATA_BYTES = 24_000_000;

    private WaveformExcerptMatcher() {}

    record Fingerprint(double[] envelope, double durationSeconds) {}

    /** Returns null if a file isn't a supported, bounded PCM WAV. */
    static Fingerprint read(InputStream source, long size) {
        if (source == null) return null;
        try (InputStream in = source) {
            if (size < 44 || size > MAX_DATA_BYTES + 65_536) return null;
            byte[] riff = in.readNBytes(RIFF_HEADER_BYTES);
            if (riff.length != RIFF_HEADER_BYTES || !tag(riff, 0, "RIFF")
                    || !tag(riff, 8, "WAVE")) {
                return null;
            }
            long consumed = RIFF_HEADER_BYTES;
            boolean validFormat = false;
            // Inspect only a bounded number of bytes before the audio payload.
            while (consumed + 8 <= size && consumed < 65_536) {
                byte[] chunk = in.readNBytes(8);
                if (chunk.length != 8) return null;
                consumed += 8;
                long length = uint32(chunk, 4);
                long padding = length & 1;
                if (length + padding > size - consumed) return null;
                if (tag(chunk, 0, "fmt ")) {
                    if (length < 16) return null;
                    byte[] format = in.readNBytes(16);
                    if (format.length != 16) return null;
                    validFormat = uint16(format, 0) == 1 // PCM
                            && uint16(format, 2) == 1 // mono
                            && uint32(format, 4) == SAMPLE_RATE
                            && uint16(format, 12) == 2 // block alignment
                            && uint16(format, 14) == 16; // bits per sample
                    in.skipNBytes(length - 16 + padding);
                } else if (tag(chunk, 0, "data")) {
                    if (!validFormat || length <= 0 || length > MAX_DATA_BYTES
                            || (length & 1) != 0) return null;
                    return readSamples(in, length);
                } else {
                    in.skipNBytes(length + padding);
                }
                consumed += length + padding;
            }
            return null;
        } catch (IOException ignored) {
            // Fingerprinting is best effort, never a reason to reject valid audio.
            return null;
        }
    }

    private static Fingerprint readSamples(InputStream in, long dataBytes) throws IOException {
        int steps = (int) (dataBytes / (SAMPLES_PER_STEP * 2L));
        if (steps < MIN_MATCH_STEPS) return null;
        double[] energy = new double[steps];
        byte[] block = new byte[SAMPLES_PER_STEP * 2];
        for (int i = 0; i < steps; i++) {
            if (in.readNBytes(block, 0, block.length) != block.length) return null;
            long sumSquares = 0;
            for (int j = 0; j < block.length; j += 2) {
                short sample = (short) ((block[j] & 0xff) | (block[j + 1] << 8));
                sumSquares += (long) sample * sample;
            }
            energy[i] = (double) sumSquares / SAMPLES_PER_STEP;
        }
        // Smooth adjacent 50 ms blocks to reduce sensitivity to arbitrary
        // excerpt start offsets and AAC codec padding, then compress levels.
        double[] envelope = new double[steps - 1];
        for (int i = 0; i < envelope.length; i++) {
            envelope[i] = Math.log1p(Math.sqrt((energy[i] + energy[i + 1]) / 2.0) / 512.0);
        }
        return new Fingerprint(envelope, dataBytes / (2.0 * SAMPLE_RATE));
    }

    /** Finds the clip's start in the original recording, in seconds. */
    static Double findOffsetSeconds(Fingerprint recording, Fingerprint excerpt) {
        if (recording == null || excerpt == null
                || recording.durationSeconds() < excerpt.durationSeconds() + 2.0) {
            return null;
        }
        double[] reference = recording.envelope();
        double[] sample = excerpt.envelope();
        int n = sample.length - 2 * EDGE_STEPS;
        if (n < MIN_MATCH_STEPS || reference.length < sample.length) {
            return null;
        }

        double sumY = 0, sumY2 = 0;
        for (int i = EDGE_STEPS; i < sample.length - EDGE_STEPS; i++) {
            sumY += sample[i];
            sumY2 += sample[i] * sample[i];
        }
        double meanY = sumY / n;
        double varY = sumY2 / n - meanY * meanY;
        if (varY < MIN_VARIANCE) {
            return null; // Silence or near-constant audio isn't identifiable.
        }

        double bestCorrelation = MIN_CORRELATION;
        int bestStart = -1;
        // Each candidate uses the same aligned 8+ second portion of the clip.
        // Evaluating Pearson correlation makes matching tolerant to codec gains
        // and independently re-encoded AAC while requiring a very strong match.
        for (int start = 0; start <= reference.length - sample.length; start++) {
            double sumX = 0, sumX2 = 0, sumXY = 0;
            for (int i = EDGE_STEPS; i < sample.length - EDGE_STEPS; i++) {
                double x = reference[start + i];
                double y = sample[i];
                sumX += x;
                sumX2 += x * x;
                sumXY += x * y;
            }
            double meanX = sumX / n;
            double varX = sumX2 / n - meanX * meanX;
            if (varX < MIN_VARIANCE) {
                continue;
            }
            double covariance = sumXY / n - meanX * meanY;
            double correlation = covariance / Math.sqrt(varX * varY);
            if (correlation > bestCorrelation) {
                bestCorrelation = correlation;
                bestStart = start;
            }
        }
        return bestStart < 0 ? null : bestStart * STEP_SECONDS;
    }

    /** Builds timestamps relative to the excerpt without altering the cached transcript. */
    static SubtitleResponse excerpt(SubtitleResponse original, double start, double duration) {
        double end = start + duration;
        List<SubtitleResponse.Segment> segments = new ArrayList<>();
        for (var segment : original.segments()) {
            if (segment.end() > start && segment.start() < end) {
                segments.add(new SubtitleResponse.Segment(
                        Math.max(0, segment.start() - start),
                        Math.min(duration, segment.end() - start),
                        segment.text(), segment.noSpeechProb(), segment.avgLogProb()));
            }
        }
        List<SubtitleResponse.Word> words = new ArrayList<>();
        for (var word : original.words()) {
            if (word.end() > start && word.start() < end) {
                words.add(new SubtitleResponse.Word(
                        Math.max(0, word.start() - start),
                        Math.min(duration, word.end() - start), word.word()));
            }
        }
        String text = segments.isEmpty()
                ? String.join(" ", words.stream().map(SubtitleResponse.Word::word).toList()).trim()
                : String.join(" ", segments.stream().map(SubtitleResponse.Segment::text).toList()).trim();
        return new SubtitleResponse(text, duration, segments, words);
    }

    private static boolean tag(byte[] bytes, int offset, String tag) {
        for (int i = 0; i < tag.length(); i++) {
            if (bytes[offset + i] != (byte) tag.charAt(i)) return false;
        }
        return true;
    }

    private static int uint16(byte[] bytes, int pos) {
        return (bytes[pos] & 0xff) | ((bytes[pos + 1] & 0xff) << 8);
    }

    private static long uint32(byte[] bytes, int pos) {
        return (uint16(bytes, pos) & 0xffffL) | ((uint16(bytes, pos + 2) & 0xffffL) << 16);
    }
}
