package edu.adelaide.kali.service;

import org.springframework.web.multipart.MultipartFile;

public interface TranscriptionService {
    String transcribe(MultipartFile audioFile);
}