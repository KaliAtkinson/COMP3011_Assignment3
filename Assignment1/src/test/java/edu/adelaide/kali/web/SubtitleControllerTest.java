package edu.adelaide.kali.web;

import edu.adelaide.kali.dto.SubtitleResponse;
import edu.adelaide.kali.service.SubtitleService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * AI Assistance:
 * This test suite was developed and refined with advice, guidance,
 * and code assistance from OpenAI ChatGPT.
 * Test implementations were reviewed and validated as part of
 * the development process.
 */
@WebMvcTest(SubtitleController.class)
class SubtitleControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubtitleService subtitleService;

    @Test
    void returnsTimestampedSubtitles() throws Exception {
        var response = new SubtitleResponse("Hello", 2.0,
                List.of(new SubtitleResponse.Segment(0.0, 2.0, "Hello", 0.01, -0.2)),
                List.of(new SubtitleResponse.Word(0.0, 1.0, "Hello")));
        when(subtitleService.transcribe(any())).thenReturn(response);

        var audio = new MockMultipartFile("audio", "speech.wav", "audio/wav", new byte[]{1, 2});
        mockMvc.perform(multipart("/api/v1/subtitles").file(audio))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Hello"))
                .andExpect(jsonPath("$.duration").value(2.0))
                .andExpect(jsonPath("$.segments[0].start").value(0.0))
                .andExpect(jsonPath("$.words[0].word").value("Hello"));
    }

    @Test
    void rejectsEmptyAudioWithoutCallingTranscriptionService() throws Exception {
        var empty = new MockMultipartFile("audio", "empty.wav", "audio/wav", new byte[0]);
        mockMvc.perform(multipart("/api/v1/subtitles").file(empty))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(subtitleService);
    }
}
