package edu.adelaide.kali.service;

import edu.adelaide.kali.dto.SubtitleResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;

/**
 * Repeat a clearly fake timed caption for local UI testing (no OpenAI calls).
 * A single three-second cue can disappear before a long video's audio is
 * extracted and uploaded. Periodic cues let developers verify toggling and
 * seeking even when the mock response arrives after the first few seconds.
 *
 * AI Assistance: Developed with advice and code assistance from OpenAI ChatGPT.
 */
@Service
@Profile("local")
public class MockSubtitleService implements SubtitleService {
    @Override
    public SubtitleResponse transcribe(MultipartFile audioFile) {
        if (audioFile == null || audioFile.isEmpty()) {
            throw new IllegalArgumentException("Audio file must not be empty");
        }
        List<SubtitleResponse.Segment> segments = new ArrayList<>();
        List<SubtitleResponse.Word> words = new ArrayList<>();
        // This is deliberately test data, not transcribed speech. It covers
        // typical demo-video lengths and has gaps in which no text is shown.
        for (int second = 0; second < 180; second += 10) {
            segments.add(new SubtitleResponse.Segment(second, second + 6.0,
                    "Local subtitle preview (mock server)", 0.0, 0.0));
            words.add(new SubtitleResponse.Word(second, second + 6.0,
                    "Local subtitle preview (mock server)"));
        }
        return new SubtitleResponse("Local subtitle preview (mock server)", 180.0,
                segments, words);
    }
}
