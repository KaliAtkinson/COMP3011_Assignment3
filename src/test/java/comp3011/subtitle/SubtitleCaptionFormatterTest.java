/*
 * AI Assistance:
 * This test suite was developed and refined with advice, guidance,
 * and code assistance from OpenAI ChatGPT.
 * Test implementations were reviewed and validated as part of
 * the development process.
 */
package comp3011.subtitle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class SubtitleCaptionFormatterTest {
    @Test
    void shortPhraseUsesOneLineAndNormalisesWhitespace() {
        assertEquals(List.of("Hello, world!"), SubtitleCaptionFormatter.lines("  Hello,   world!  "));
    }

    @Test
    void blankSubtitleProducesNoLines() {
        assertEquals(List.of(), SubtitleCaptionFormatter.lines(" \n \t "));
    }

    @Test
    void longerPhraseFavorsPunctuationWhenBreaking() {
        String phrase = "I'm glad you could join us today, please take a seat whenever you like.";
        List<String> lines = SubtitleCaptionFormatter.lines(phrase);
        assertEquals(2, lines.size());
        assertTrue(lines.getFirst().endsWith(","), "Prefer a break after punctuation");
    }

    @Test
    void longPhrasesNeverExceedTwoLinesOrFortyTwoCharactersEach() {
        String phrase = "This is a particularly long utterance that could not possibly "
                + "fit inside two lines with the standard subtitle character limits.";
        List<String> lines = SubtitleCaptionFormatter.lines(phrase);
        assertEquals(2, lines.size());
        assertTrue(lines.stream().allMatch(s -> s.length() <= 42));
        assertTrue(lines.getLast().endsWith("…"), "Long segments have a visible truncation marker");
    }

    @Test
    void veryLongUnbrokenWordDoesNotOverflowTheLineLimit() {
        List<String> lines = SubtitleCaptionFormatter.lines("a".repeat(100));
        assertEquals(2, lines.size());
        assertTrue(lines.stream().allMatch(s -> s.length() <= 42));
    }
}
