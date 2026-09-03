package edu.adelaide.kali.web;

import edu.adelaide.kali.dto.TranscriptionResponse;
import edu.adelaide.kali.service.TranscriptionService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
public class TranscriptionController {

    private final TranscriptionService transcriptionService;

    public TranscriptionController(TranscriptionService transcriptionService) {
        this.transcriptionService = transcriptionService;
    }

    @PostMapping(value = "/api/v1/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TranscriptionResponse transcribe(@RequestParam("audio") MultipartFile audioFile) {
        String text = transcriptionService.transcribe(audioFile);
        return new TranscriptionResponse(text);
    }
}