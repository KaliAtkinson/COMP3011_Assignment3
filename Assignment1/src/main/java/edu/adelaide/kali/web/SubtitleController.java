package edu.adelaide.kali.web;

import edu.adelaide.kali.dto.SubtitleResponse;
import edu.adelaide.kali.service.SubtitleService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** The player posts extracted original media audio under the field "audio". */
@RestController
public class SubtitleController {
    private final SubtitleService subtitleService;

    public SubtitleController(SubtitleService subtitleService) {
        this.subtitleService = subtitleService;
    }

    @PostMapping(value = "/api/v1/subtitles", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<SubtitleResponse> subtitles(@RequestParam("audio") MultipartFile audioFile) {
        if (audioFile.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        return ResponseEntity.ok(subtitleService.transcribe(audioFile));
    }
}
