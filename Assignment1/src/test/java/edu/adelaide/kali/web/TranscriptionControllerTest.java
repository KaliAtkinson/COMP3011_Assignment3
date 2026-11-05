package edu.adelaide.kali.web;

import edu.adelaide.kali.service.TranscriptionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(TranscriptionController.class)
class TranscriptionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TranscriptionService transcriptionService;

    @Test
    void transcribeEndpointReturnsServiceResult() throws Exception {
        when(transcriptionService.transcribe(any())).thenReturn("hello world");

        MockMultipartFile audio = new MockMultipartFile(
            "audio", "recording.webm", "audio/webm", "fake-audio-bytes".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/transcribe").file(audio))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.text").value("hello world"));
    }
}