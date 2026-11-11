/*
 * AI Assistance:
 * This test suite was developed and refined with advice, guidance,
 * and code assistance from OpenAI ChatGPT.
 * Test implementations were reviewed and validated as part of
 * the development process.
 */
package comp3011.subtitle;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import comp3011.InfoFrame;
import comp3011.InfoVideo;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

class FrameSubtitleRendererTest {
    private static final int WIDTH = 640;
    private static final int HEIGHT = 360;

    @Test
    void drawsWhiteTextAndBlackOutlineNearBottomOnlyWhenCueIsActive() throws Exception {
        FrameSubtitleRenderer renderer = renderer();
        Frame before = frame(80);
        byte[] original = pixels(before);
        renderer.process(before, new InfoFrame(1, 250_000));
        assertArrayEquals(original, pixels(before), "No dialogue must leave image untouched");

        Frame active = frame(80);
        renderer.process(active, new InfoFrame(2, 1_500_000));
        assertFalse(Arrays.equals(original, pixels(active)));
        assertTrue(hasBrightPixel(active), "Subtitle should include bright white glyph pixels");
        assertTrue(hasDarkPixel(active), "Outline should include black pixels");
        assertTrue(topUnchanged(original, pixels(active)), "Subtitle must not cover the scene centre");

        Frame after = frame(80);
        renderer.process(after, new InfoFrame(3, 4_000_000));
        assertArrayEquals(original, pixels(after));
    }

    @Test
    void renderedFrameIsDeterministicAcrossWorkerThreads() throws Exception {
        FrameSubtitleRenderer renderer = renderer();
        Frame reference = frame(120);
        renderer.process(reference, new InfoFrame(6, 1_200_000));
        byte[] expected = pixels(reference);

        try (ExecutorService workers = Executors.newFixedThreadPool(6)) {
            List<Future<byte[]>> results = new java.util.ArrayList<>();
            for (int i = 0; i < 20; i++) {
                results.add(workers.submit(() -> {
                    Frame next = frame(120);
                    renderer.process(next, new InfoFrame(6, 1_200_000));
                    return pixels(next);
                }));
            }
            for (Future<byte[]> result : results) {
                assertArrayEquals(expected, result.get());
            }
        }
    }

    @Test
    void liveTimelineAppearsAfterTranscriptionAndStopsWhenDisabled() throws Exception {
        java.util.concurrent.atomic.AtomicReference<SubtitleTimeline> current =
                new java.util.concurrent.atomic.AtomicReference<>(SubtitleTimeline.empty());
        java.util.concurrent.atomic.AtomicBoolean enabled = new java.util.concurrent.atomic.AtomicBoolean(true);
        FrameSubtitleRenderer renderer = new FrameSubtitleRenderer(current::get, enabled::get);
        renderer.initialise(new InfoVideo("test.mp4", 240, WIDTH, HEIGHT, Frame.DEPTH_UBYTE, 3,
                WIDTH * 3, 24, 24, 41667, 0));

        Frame noTranscript = frame(100);
        byte[] original = pixels(noTranscript);
        renderer.process(noTranscript, new InfoFrame(1, 1_500_000));
        assertArrayEquals(original, pixels(noTranscript));

        SubtitleTranscript transcript = new SubtitleTranscript("New caption", 5,
                List.of(new SubtitleTranscript.Segment(1, 3, "New caption", 0, 0)), List.of());
        current.set(SubtitleTimeline.fromTranscript(transcript));
        Frame withTranscript = frame(100);
        renderer.process(withTranscript, new InfoFrame(2, 1_500_000));
        assertFalse(Arrays.equals(original, pixels(withTranscript)));

        enabled.set(false);
        Frame turnedOff = frame(100);
        renderer.process(turnedOff, new InfoFrame(3, 1_500_000));
        assertArrayEquals(original, pixels(turnedOff));
    }

    @Test
    void liveOverlayProcessorNeverBurnsCaptionsIntoPrefetchedFrames() throws Exception {
        SubtitleTimeline timeline = SubtitleTimeline.fromTranscript(new SubtitleTranscript("Hello world", 10,
                List.of(new SubtitleTranscript.Segment(1, 3, "Hello world", 0, 0)), List.of()));
        FrameSubtitleRenderer renderer = FrameSubtitleRenderer.forLiveOverlay(() -> timeline, () -> true);
        renderer.initialise(new InfoVideo("test.mp4", 240, WIDTH, HEIGHT, Frame.DEPTH_UBYTE, 3,
                WIDTH * 3, 24, 24, 41667, 0));
        Frame video = frame(90);
        byte[] original = pixels(video);
        renderer.process(video, new InfoFrame(7, 1_500_000));
        assertArrayEquals(original, pixels(video),
                "Captions cannot be irreversibly embedded in frames ahead of playback");
    }

    @Test
    void overlayHasTransparentBackgroundAndVisibleWhiteAndBlackGlyphs() {
        FrameSubtitleRenderer renderer = new FrameSubtitleRenderer(SubtitleTimeline.empty());
        int[] argb = renderer.overlayArgb("Hello world", WIDTH, HEIGHT);
        assertEquals(WIDTH * HEIGHT, argb.length);
        assertEquals(0, argb[0], "The video itself must remain unobscured");
        assertEquals(0, argb[(HEIGHT / 2) * WIDTH + WIDTH / 2]);
        boolean white = false;
        boolean black = false;
        for (int pixel : argb) {
            if ((pixel >>> 24) > 220) {
                white |= (pixel & 0xffffff) == 0xffffff;
                black |= (pixel & 0xffffff) == 0;
            }
        }
        assertTrue(white, "Caption text should be white");
        assertTrue(black, "Caption outline should be black");
        assertArrayEquals(new int[WIDTH * HEIGHT], renderer.overlayArgb("", WIDTH, HEIGHT));
    }

    @Test
    void preservesFourthAlphaChannel() throws Exception {
        FrameSubtitleRenderer renderer = renderer();
        Frame bgra = new Frame(WIDTH, HEIGHT, Frame.DEPTH_UBYTE, 4);
        ByteBuffer bytes = ((ByteBuffer) bgra.image[0]).duplicate();
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                bytes.put(y * bgra.imageStride + x * 4 + 3, (byte) 191);
            }
        }
        renderer.process(bgra, new InfoFrame(4, 1_100_000));
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                assertTrue(Byte.toUnsignedInt(bytes.get(y * bgra.imageStride + x * 4 + 3)) == 191);
            }
        }
    }

    private FrameSubtitleRenderer renderer() throws Exception {
        SubtitleTranscript transcript = new SubtitleTranscript("Hello world", 10,
                List.of(new SubtitleTranscript.Segment(1, 3, "Hello world", 0, 0)), List.of());
        FrameSubtitleRenderer renderer = new FrameSubtitleRenderer(SubtitleTimeline.fromTranscript(transcript));
        renderer.initialise(new InfoVideo("test.mp4", 240, WIDTH, HEIGHT, Frame.DEPTH_UBYTE, 3,
                WIDTH * 3, 24, 24, 41667, 0));
        return renderer;
    }

    private static Frame frame(int shade) {
        Frame frame = new Frame(WIDTH, HEIGHT, Frame.DEPTH_UBYTE, 3);
        ByteBuffer pixels = ((ByteBuffer) frame.image[0]).duplicate();
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH * 3; x++) {
                pixels.put(y * frame.imageStride + x, (byte) shade);
            }
        }
        return frame;
    }

    private static byte[] pixels(Frame frame) {
        ByteBuffer data = ((ByteBuffer) frame.image[0]).duplicate();
        data.clear();
        byte[] bytes = new byte[data.remaining()];
        data.get(bytes);
        return bytes;
    }

    private static boolean topUnchanged(byte[] before, byte[] after) {
        for (int y = 0; y < HEIGHT / 2; y++) {
            int begin = y * WIDTH * 3;
            if (!Arrays.equals(Arrays.copyOfRange(before, begin, begin + WIDTH * 3),
                    Arrays.copyOfRange(after, begin, begin + WIDTH * 3))) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasBrightPixel(Frame frame) {
        ByteBuffer pixels = (ByteBuffer) frame.image[0];
        for (int y = HEIGHT / 2; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (Byte.toUnsignedInt(pixels.get(y * frame.imageStride + x * 3)) > 220) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean hasDarkPixel(Frame frame) {
        ByteBuffer pixels = (ByteBuffer) frame.image[0];
        for (int y = HEIGHT / 2; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (Byte.toUnsignedInt(pixels.get(y * frame.imageStride + x * 3)) < 40) {
                    return true;
                }
            }
        }
        return false;
    }
}
