package comp3011.subtitle;

import java.util.List;
import java.util.Objects;

/**
 * Formats one phrase into at most two subtitle lines, each at most 42 characters.
 *
 * <p>Prefer a single line when the phrase fits. When a break is necessary,
 * favour punctuation and reasonably balanced lines. Very long Whisper segments
 * are shortened as a temporary fallback until word-level cue splitting is added.
 *
 * <p>AI Assistance: Developed with advice and code assistance from OpenAI ChatGPT.
 */
public final class SubtitleCaptionFormatter {
    public static final int MAX_CHARACTERS_PER_LINE = 42;

    private SubtitleCaptionFormatter() {
    }

    public static List<String> lines(String rawText) {
        Objects.requireNonNull(rawText, "rawText");
        String text = rawText.replaceAll("\\s+", " ").trim();
        if (text.isEmpty()) {
            return List.of();
        }
        if (text.length() <= MAX_CHARACTERS_PER_LINE) {
            return List.of(text);
        }

        int split = chooseLineBreak(text);
        if (split < 1) {
            // A single unbroken word cannot be wrapped at whitespace.
            // This exceptional case is handled without overflowing the frame.
            return List.of(text.substring(0, MAX_CHARACTERS_PER_LINE),
                    shorten(text.substring(MAX_CHARACTERS_PER_LINE), MAX_CHARACTERS_PER_LINE));
        }

        String first = text.substring(0, split).trim();
        String second = shorten(text.substring(split).trim(), MAX_CHARACTERS_PER_LINE);
        return second.isEmpty() ? List.of(first) : List.of(first, second);
    }

    private static int chooseLineBreak(String text) {
        int bestIndex = -1;
        int bestScore = Integer.MIN_VALUE;
        int max = Math.min(MAX_CHARACTERS_PER_LINE, text.length() - 1);
        for (int index = 1; index <= max; index++) {
            if (text.charAt(index) != ' ') {
                continue;
            }
            int remaining = text.length() - index - 1;
            // Prefer not to lose any dialogue when it can fit in two lines.
            // For longer phrases, fill more of the first line before truncation.
            int score = remaining <= MAX_CHARACTERS_PER_LINE
                    ? 1000 - Math.abs(index - remaining) * 3
                    : index * 3;
            if (index >= 15 && isPunctuation(text.charAt(index - 1))) {
                score += 100;
            }
            if (score > bestScore) {
                bestScore = score;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    private static boolean isPunctuation(char character) {
        return character == ',' || character == '.' || character == ';'
                || character == ':' || character == '!' || character == '?';
    }

    private static String shorten(String text, int maxLength) {
        if (text.length() <= maxLength) {
            return text;
        }
        // Make the loss of excess words explicit rather than silently cutting
        // off a phrase. Do not exceed the required limit, including the ellipsis.
        int end = maxLength - 1;
        int lastSpace = text.lastIndexOf(' ', end);
        if (lastSpace >= end / 2) {
            end = lastSpace;
        }
        return text.substring(0, end).stripTrailing() + "…";
    }
}
