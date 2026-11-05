package edu.adelaide.kali.service;

import edu.adelaide.kali.dto.SubtitleResponse;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/** Fixed test caption for local UI development; never calls or charges Whisper. */
@Service
@Profile("local")
public class MockSubtitleService implements SubtitleService {
    @Override
    public SubtitleResponse transcribe(MultipartFile audioFile) {
        return new SubtitleResponse("Local subtitle preview", 3.0,
                List.of(new SubtitleResponse.Segment(0.0, 3.0, "Local subtitle preview", 0.0, 0.0)),
                List.of(new SubtitleResponse.Word(0.0, 1.0, "Local"),
                        new SubtitleResponse.Word(1.0, 2.0, "subtitle"),
                        new SubtitleResponse.Word(2.0, 3.0, "preview")));
    }
}
