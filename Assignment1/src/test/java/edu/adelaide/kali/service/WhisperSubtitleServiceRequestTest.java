package edu.adelaide.kali.service;

import edu.adelaide.kali.config.AudioUsageTracker;
import edu.adelaide.kali.config.OpenAiProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/*
 * AI Assistance:
 * This test suite was developed and refined with advice, guidance,
 * and code assistance from OpenAI ChatGPT.
 * Test implementations were reviewed and validated as part of
 * the development process.
 */
class WhisperSubtitleServiceRequestTest {
    @Test
    void uploadsAudioWithRequiredWhisperParametersAndRecordsSeconds() {
        var builder = RestClient.builder();
        var server = MockRestServiceServer.bindTo(builder).build();
        var tracker = new AudioUsageTracker();
        var service = new WhisperSubtitleService(builder.build(),
                new OpenAiProperties("https://api.openai.com/v1/audio/transcriptions", "ignored", "test-key"),
                tracker);

        server.expect(requestTo("https://api.openai.com/v1/audio/transcriptions"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andExpect(request -> {
                    String body = ((MockClientHttpRequest) request).getBodyAsString();
                    assertTrue(body.contains("name=\"model\""));
                    assertTrue(body.contains("whisper-1"));
                    assertTrue(body.contains("name=\"response_format\""));
                    assertTrue(body.contains("verbose_json"));
                    assertTrue(body.contains("name=\"timestamp_granularities[]\""));
                    assertTrue(body.contains("word"));
                    assertTrue(body.contains("segment"));
                    assertTrue(body.contains("filename=\"speech.wav\""));
                })
                .andRespond(withSuccess("""
                        {"text":"Hello","duration":2.5,
                         "segments":[{"start":0.0,"end":2.5,"text":"Hello","no_speech_prob":0.0,"avg_logprob":-0.1}],
                         "words":[{"start":0.0,"end":1.0,"word":"Hello"}],
                         "usage":{"type":"duration","seconds":3}}
                        """, MediaType.APPLICATION_JSON));

        var result = service.transcribe(new MockMultipartFile("audio", "speech.wav", "audio/wav", new byte[]{1, 2, 3}));

        assertEquals("Hello", result.text());
        assertEquals(1, result.words().size());
        assertEquals(3.0, tracker.getSeconds());
        server.verify();
    }
}
