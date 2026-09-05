package edu.adelaide.kali.service;

import edu.adelaide.kali.config.OpenAiProperties;
import edu.adelaide.kali.exception.TranscriptionException;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@Service
@Profile("!local")
public class OpenAiTranscriptionService implements TranscriptionService {

    private final RestClient restClient;
    private final OpenAiProperties properties;

    public OpenAiTranscriptionService(RestClient restClient, OpenAiProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public String transcribe(MultipartFile audioFile) {
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("model", properties.model());
            body.add("file", audioFile.getResource());

            Map<?, ?> response = restClient.post()
                .uri(properties.url())
                .header("Authorization", "Bearer " + properties.key())
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(Map.class);

            return String.valueOf(response.get("text"));

        } catch (Exception ex) {
            throw new TranscriptionException("Failed to transcribe audio: " + ex.getMessage());
        }
    }
}