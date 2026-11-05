package edu.adelaide.kali.service;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
@Profile("local")
public class MockTranscriptionService implements TranscriptionService {

    @Override
    public String transcribe(MultipartFile audioFile) {
        return "This is a placeholder transcription (mock service, size=" + audioFile.getSize() + " bytes).";
    }
}