package comp3011.subtitle;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
    // Split contractions as one lexical word, but allow hyphenated words to align
    // with Whisper's separate word tokens (e.g. X-D versus X / D).
    private static final Pattern LEXICAL_WORD = Pattern.compile(
            "[\\p{L}\\p{N}]+(?:['’][\\p{L}\\p{N}]+)*");

    private record TimedToken(double start, double end, String text, boolean spaced) { }
    private record TextWord(int start, int end, String value) { }

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
                split(punctuatedWords(segment.text(), belonging), result);
            }
        }
        return result;
    }

    /**
     * Recover punctuation only when the segment and its timed words agree on
     * their lexical sequence. Otherwise trust the word-level data unchanged;
     * guessing punctuation from a mismatched transcript risks corrupting it.
     */
    private static List<TimedToken> punctuatedWords(String segmentText,
                                                    List<SubtitleTranscript.Word> words) {
        List<TextWord> sourceWords = lexicalWords(segmentText);
        List<List<TextWord>> wordTokens = new ArrayList<>(words.size());
        int count = 0;
        for (SubtitleTranscript.Word word : words) {
            List<TextWord> tokens = lexicalWords(word.word());
            if (tokens.isEmpty()) { // Whisper occasionally returns standalone punctuation.
                return unformattedWords(words);
            }
            count += tokens.size();
            wordTokens.add(tokens);
        }
        if (count != sourceWords.size()) {
            return unformattedWords(words);
        }
        int position = 0;
        for (List<TextWord> tokens : wordTokens) {
            for (TextWord token : tokens) {
                if (!canonical(token.value()).equals(canonical(sourceWords.get(position++).value()))) {
                    return unformattedWords(words);
                }
            }
        }

        List<TimedToken> restored = new ArrayList<>(words.size());
        int offset = 0;
        for (int i = 0; i < words.size(); i++) {
            int first = offset;
            offset += wordTokens.get(i).size();
            int start = i == 0 ? 0 : sourceWords.get(first).start();
            int end = offset == sourceWords.size()
                    ? segmentText.length() : sourceWords.get(offset).start();
            String original = clean(segmentText.substring(start, end));
            // When the source joins words with a hyphen, do not add a space.
            boolean spaced = i != 0 && segmentText.substring(
                    sourceWords.get(first - 1).end(), sourceWords.get(first).start())
                    .codePoints().anyMatch(Character::isWhitespace);
            SubtitleTranscript.Word word = words.get(i);
            restored.add(new TimedToken(word.start(), word.end(), original, spaced));
        }
        return restored;
    }

    private static List<TextWord> lexicalWords(String text) {
        List<TextWord> result = new ArrayList<>();
        Matcher matcher = LEXICAL_WORD.matcher(text);
        while (matcher.find()) {
            result.add(new TextWord(matcher.start(), matcher.end(), matcher.group()));
        }
        return result;
    }

    private static String canonical(String text) {
        return text.replace('’', '\'').toLowerCase(Locale.ROOT);
    }

    private static List<TimedToken> unformattedWords(List<SubtitleTranscript.Word> words) {
        List<TimedToken> result = new ArrayList<>(words.size());
        for (SubtitleTranscript.Word word : words) {
            String token = clean(word.word());
            result.add(new TimedToken(word.start(), word.end(), token,
                    !token.matches("^[,.;:!?%\\)\\]]+$")));
        }
        return result;
    }

    private static void split(List<TimedToken> words,
                              List<SubtitleTimeline.Cue> result) {
        StringBuilder caption = new StringBuilder();
        double start = 0.0;
        double end = 0.0;

        for (TimedToken word : words) {
            String token = word.text();
            if (token.isEmpty()) {
                continue;
            }
            if (caption.isEmpty()) {
                start = word.start();
                end = word.end();
                caption.append(token);
                continue;
            }

            String combined = append(caption.toString(), token, word.spaced());
            boolean pause = word.start() - end >= PAUSE_SECONDS;
            boolean completeSentence = endsSentence(caption)
                    && end - start >= 0.8;
            boolean tooLong = !fitsTwoLines(combined);
            boolean tooSlow = word.end() - start > MAX_PHRASE_SECONDS;

            // A source hyphen can tie several timed tokens into one visible word.
            // Avoid splitting halfway through such a word.
            if (word.spaced() && (pause || completeSentence || tooLong || tooSlow)) {
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

    private static String append(String existing, String token, boolean spaced) {
        return existing + (spaced ? " " : "") + token;
    }

    private static boolean endsSentence(CharSequence text) {
        return !text.isEmpty() && ".!?".indexOf(text.charAt(text.length() - 1)) >= 0;
    }

    /** Exact whitespace-wrapability check, not merely a total length <= 84. */
    static boolean fitsTwoLines(String text) {
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
