package edu.adelaide.kali.web;

import edu.adelaide.kali.config.AudioUsageTracker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/*
 * AI Assistance:
 * This test suite was developed and refined with advice, guidance,
 * and code assistance from OpenAI ChatGPT.
 * Test implementations were reviewed and validated as part of
 * the development process.
 */
@WebMvcTest(AudioUsageController.class)
class AudioUsageControllerTest {
    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    AudioUsageTracker tracker;

    @Test
    void returnsExactlyOneDurationField() throws Exception {
        when(tracker.getSeconds()).thenReturn(12.75);
        mockMvc.perform(get("/api/v1/global/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage").value(12.75))
                .andExpect(jsonPath("$.length()").value(1));
    }
}
