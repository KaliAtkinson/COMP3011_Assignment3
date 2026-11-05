package edu.adelaide.kali.service;

import edu.adelaide.kali.dto.SubtitleResponse;
import org.springframework.web.multipart.MultipartFile;

/** Produces timed subtitles from an uploaded recording. */
public interface SubtitleService {
    SubtitleResponse transcribe(MultipartFile audioFile);
}
