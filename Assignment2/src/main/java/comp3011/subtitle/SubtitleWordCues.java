package comp3011.subtitle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Builds readable, word-timed subtitle phrases from Whisper's word timestamps.
 *
 * <p>Whisper segment text can cover many seconds of speech. Rendering it as one
 * caption both hides words (the renderer has a two-line limit) and makes the
 * caption appear before some of its words have been spoken. This helper keeps
 * each word's original timing, breaking at pauses, punctuation, or when the
 * next word would exceed the caption layout or display duration.
 *
 * <p>All state is local to construction. The resulting cues are immutable and
 * are read by the existing seek-safe SubtitleTimeline.
 *
 * <p>AI Assistance: Developed with advice and code assistance from OpenAI ChatGPT.
 */
final class SubtitleWordCues {
    private static final double PAUSE_SECONDS = 0.65;
    private static final double MAX_PHRASE_SECONDS = 6.5;
    private static final double WORD_BOUNDARY_TOLERANCE_SECONDS = 0.1;
    private static final int MAX_LINE_CHARS = SubtitleCaptionFormatter.MAX_CHARACTERS_PER_LINE;

    private SubtitleWordCues() { }

    /**
     * Convert the usable segments to word-timed phrases. Falls back to an
     * unsplit segment when Whisper supplied no words within its time range.
     */
    static List<SubtitleTimeline.Cue> build(SubtitleTranscript transcript) {
        List<SubtitleTranscript.Segment> segments = new ArrayList<>(transcript.segments());
        // Stable sorting preserves Whisper's order for identical start times.
        segments.sort(Comparator.comparingDouble(SubtitleTranscript.Segment::start));

        List<SubtitleTranscript.Word> words = new ArrayList<>(transcript.words());
        words.sort(Comparator.comparingDouble(SubtitleTranscript.Word::start));
        boolean[] assigned = new boolean[words.size()];
        List<SubtitleTimeline.Cue> result = new ArrayList<>();

        for (SubtitleTranscript.Segment segment : segments) {
            if (segment.noSpeechProb() > 0.6 && segment.avgLogProb() < -1.0) {
                continue; // Match the assignment's two-part silence rule.
            }
            String fallback = clean(segment.text());
            if (fallback.isEmpty()) {
                continue;
            }

            List<SubtitleTranscript.Word> belonging = new ArrayList<>();
            for (int i = 0; i < words.size(); i++) {
                if (assigned[i]) {
                    continue;
                }
                SubtitleTranscript.Word word = words.get(i);
                double midpoint = word.start() + (word.end() - word.start()) / 2.0;
                if (midpoint >= segment.start() - WORD_BOUNDARY_TOLERANCE_SECONDS
                        && midpoint <= segment.end() + WORD_BOUNDARY_TOLERANCE_SECONDS
                        && !clean(word.word()).isEmpty()) {
                    belonging.add(word);
                    assigned[i] = true; // Do not repeat a boundary word in two segments.
                }
            }

            if (belonging.isEmpty()) {
                result.add(cue(segment.start(), segment.end(), fallback));
            } else {
                split(belonging, result);
            }
        }
        return result;
    }

    private static void split(List<SubtitleTranscript.Word> words,
                              List<SubtitleTimeline.Cue> result) {
        StringBuilder caption = new StringBuilder();
        double start = 0.0;
        double end = 0.0;

        for (SubtitleTranscript.Word word : words) {
            String token = clean(word.word());
            if (token.isEmpty()) {
                continue;
            }
            if (caption.isEmpty()) {
                start = word.start();
                end = word.end();
                caption.append(token);
                continue;
            }

            String combined = append(caption.toString(), token);
            boolean pause = word.start() - end >= PAUSE_SECONDS;
            boolean completeSentence = endsSentence(caption)
                    && end - start >= 0.8;
            boolean tooLong = !fitsTwoLines(combined);
            boolean tooSlow = word.end() - start > MAX_PHRASE_SECONDS;

            if (pause || completeSentence || tooLong || tooSlow) {
                result.add(cue(start, end, caption.toString()));
                caption.setLength(0);
                caption.append(token);
                start = word.start();
                end = word.end();
            } else {
                caption.setLength(0);
                caption.append(combined);
                end = Math.max(end, word.end());
            }
        }
        if (!caption.isEmpty()) {
            result.add(cue(start, end, caption.toString()));
        }
    }

    private static SubtitleTimeline.Cue cue(double start, double end, String text) {
        // Whisper occasionally gives a zero-length word or segment; the timeline
        // will enforce the one-second minimum where adjacent captions permit it.
        return new SubtitleTimeline.Cue(start, Math.max(end, start + 0.001), text);
    }

    private static String clean(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").trim();
    }

    private static String append(String existing, String token) {
        // Whisper may return punctuation as a separate word token.
        return token.matches("^[,.;:!?%\\)\\]]+$")
                ? existing + token : existing + " " + token;
    }

    private static boolean endsSentence(CharSequence text) {
        return !text.isEmpty() && ".!?".indexOf(text.charAt(text.length() - 1)) >= 0;
    }

    /** Exact whitespace-wrapability check, not merely a total length <= 84. */
    private static boolean fitsTwoLines(String text) {
        if (text.length() <= MAX_LINE_CHARS) {
            return true;
        }
        if (text.length() > MAX_LINE_CHARS * 2 + 1) {
            return false;
        }
        for (int index = 1; index < text.length(); index++) {
            if (text.charAt(index) == ' '
                    && index <= MAX_LINE_CHARS
                    && text.length() - index - 1 <= MAX_LINE_CHARS) {
                return true;
            }
        }
        return false;
    }
}
