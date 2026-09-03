package edu.adelaide.kali.service;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class MockTranscriptionService implements TranscriptionService {

    @Override
    public String transcribe(MultipartFile audioFile) {
        return "This is a placeholder transcription (mock service, size=" + audioFile.getSize() + " bytes).";
    }
}