package comp3011.subtitle;

import comp3011.FrameProcessor;
import comp3011.InfoFrame;
import comp3011.InfoVideo;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Objects;
import org.bytedeco.javacv.Frame;

/**
 * Paints subtitle text directly onto a video frame using the frame timestamp.
 *
 * <p>Text is rendered into a transparent Java2D bitmap (white sans-serif text,
 * black outline) then alpha-blended into the BGR frame. Java2D runs on the
 * caller's frame-processing worker, not on the JavaFX application thread.
 * The cached bitmap is immutable after construction and can be read safely by
 * concurrent effect workers. A single-entry cache avoids accumulating a
 * bitmap for every phrase of a long movie.
 *
 * <p>This class is intentionally not wired into the live player until the
 * command-line and subtitle lifecycle integration commit. Toggling a frame
 * processor in a prefetched pipeline alone is not an immediate on-screen
 * toggle: already-processed frames must also be considered at integration.
 *
 * <p>AI Assistance: Developed with advice and code assistance from OpenAI ChatGPT.
 */
public final class FrameSubtitleRenderer extends FrameProcessor {
    private final SubtitleTimeline timeline;
    private volatile CaptionBitmap cachedCaption;

    public FrameSubtitleRenderer(SubtitleTimeline timeline) {
        this.timeline = Objects.requireNonNull(timeline, "timeline");
    }

    @Override
    public void initialise(InfoVideo info) throws Exception {
        super.initialise(info);
        cachedCaption = null;
    }

    @Override
    public void process(Frame frame, InfoFrame info) {
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(info, "info");
        if (frame.image == null || frame.image.length == 0 || frame.image[0] == null) {
            return;
        }
        var cue = timeline.atSeconds(info.timestampUs() / 1_000_000.0);
        if (cue.isEmpty()) {
            return;
        }
        if (!(frame.image[0] instanceof ByteBuffer pixels)
                || frame.imageDepth != Frame.DEPTH_UBYTE && frame.imageDepth != Frame.DEPTH_BYTE
                || frame.imageChannels < 3 || frame.imageChannels > 4) {
            throw new IllegalArgumentException("Subtitles require 8-bit, 3- or 4-channel byte-backed frames");
        }
        String text = cue.orElseThrow().text();
        CaptionBitmap bitmap = cachedCaption;
        if (bitmap == null || bitmap.width != frame.imageWidth
                || bitmap.height != frame.imageHeight || bitmap.channels != frame.imageChannels
                || bitmap.stride != frame.imageStride || !bitmap.text.equals(text)) {
            bitmap = renderCaption(text, frame.imageWidth, frame.imageHeight,
                    frame.imageChannels, frame.imageStride);
            cachedCaption = bitmap;
        }
        blend(bitmap, pixels);
    }

    private static CaptionBitmap renderCaption(String text, int width, int height,
                                               int channels, int stride) {
        List<String> lines = SubtitleCaptionFormatter.lines(text);
        if (lines.isEmpty()) {
            return new CaptionBitmap(text, width, height, channels, stride, new int[0], new int[0]);
        }

        // Scale text with the image, but keep it readable at common 360p/720p/1080p resolutions.
        int fontSize = Math.max(10, Math.min(48, Math.round(height * 0.048f)));
        Font font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
        BufferedImage measuring = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D metrics = measuring.createGraphics();
        try {
            while (fontSize > 9) {
                font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
                metrics.setFont(font);
                int widest = 0;
                for (String line : lines) {
                    widest = Math.max(widest, metrics.getFontMetrics().stringWidth(line));
                }
                if (widest <= Math.max(1, width - width / 10)) {
                    break;
                }
                fontSize--;
            }
        } finally {
            metrics.dispose();
        }

        int lineSpacing = Math.round(fontSize * 1.28f);
        int padding = Math.max(4, fontSize / 4);
        int bitmapHeight = Math.min(height, lines.size() * lineSpacing + padding * 2);
        int bottomMargin = Math.max(2, Math.round(height * 0.07f));
        int top = Math.max(0, height - bottomMargin - bitmapHeight);
        BufferedImage overlay = new BufferedImage(width, bitmapHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = overlay.createGraphics();
        try {
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setFont(font);
            FontRenderContext frc = graphics.getFontRenderContext();
            float strokeWidth = Math.max(2.0f, fontSize * 0.15f);
            graphics.setStroke(new BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
                TextLayout layout = new TextLayout(lines.get(lineIndex), font, frc);
                float x = (width - layout.getAdvance()) / 2;
                float baseline = padding + layout.getAscent() + lineIndex * lineSpacing;
                Shape outline = layout.getOutline(AffineTransform.getTranslateInstance(x, baseline));
                graphics.setColor(Color.BLACK);
                graphics.draw(outline);
                graphics.setColor(Color.WHITE);
                graphics.fill(outline);
            }
        } finally {
            graphics.dispose();
        }
        // Retain only actual glyph/outline pixels. Most of the transparent
        // overlay is empty; skipping those pixels keeps per-frame work low.
        int[] argb = overlay.getRGB(0, 0, width, bitmapHeight, null, 0, width);
        int visibleCount = 0;
        for (int color : argb) {
            if ((color >>> 24) != 0) {
                visibleCount++;
            }
        }
        int[] byteOffsets = new int[visibleCount];
        int[] colors = new int[visibleCount];
        int next = 0;
        for (int y = 0; y < bitmapHeight; y++) {
            for (int x = 0; x < width; x++) {
                int color = argb[y * width + x];
                if ((color >>> 24) != 0) {
                    byteOffsets[next] = (top + y) * stride + x * channels;
                    colors[next] = color;
                    next++;
                }
            }
        }
        return new CaptionBitmap(text, width, height, channels, stride, byteOffsets, colors);
    }

    private static void blend(CaptionBitmap caption, ByteBuffer pixels) {
        for (int index = 0; index < caption.colors.length; index++) {
            int argb = caption.colors[index];
            int alpha = argb >>> 24;
            int base = caption.byteOffsets[index];
            if (alpha == 255) {
                pixels.put(base, (byte) (argb & 0xff));
                pixels.put(base + 1, (byte) ((argb >>> 8) & 0xff));
                pixels.put(base + 2, (byte) ((argb >>> 16) & 0xff));
            } else {
                blendChannel(pixels, base, argb & 0xff, alpha);
                blendChannel(pixels, base + 1, (argb >>> 8) & 0xff, alpha);
                blendChannel(pixels, base + 2, (argb >>> 16) & 0xff, alpha);
            }
            // If this is a four-channel image, its original alpha is preserved.
        }
    }

    private static void blendChannel(ByteBuffer pixels, int offset, int overlay, int alpha) {
        int existing = Byte.toUnsignedInt(pixels.get(offset));
        int blended = (overlay * alpha + existing * (255 - alpha) + 127) / 255;
        pixels.put(offset, (byte) blended);
    }

    private record CaptionBitmap(String text, int width, int height, int channels, int stride,
                                 int[] byteOffsets, int[] colors) {
    }
}
