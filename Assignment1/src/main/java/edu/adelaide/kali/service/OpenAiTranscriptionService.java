package edu.adelaide.kali.service;

import edu.adelaide.kali.config.OpenAiProperties;
import edu.adelaide.kali.config.TokenUsageTracker;
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
    private final TokenUsageTracker tokenUsageTracker;

    public OpenAiTranscriptionService(RestClient restClient, OpenAiProperties properties,
                                       TokenUsageTracker tokenUsageTracker) {
        this.restClient = restClient;
        this.properties = properties;
        this.tokenUsageTracker = tokenUsageTracker;
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

            recordTokenUsage(response);

            return String.valueOf(response.get("text"));

        } catch (Exception ex) {
            throw new TranscriptionException("Failed to transcribe audio: " + ex.getMessage());
        }
    }

    private void recordTokenUsage(Map<?, ?> response) {
        Object usageObj = response.get("usage");
        if (!(usageObj instanceof Map<?, ?> usage)) {
            return; // no usage data in this response — nothing to record
        }

        Long inputTokens = extractLong(usage, "input_tokens");
        Long outputTokens = extractLong(usage, "output_tokens");

        if (inputTokens != null) {
            tokenUsageTracker.addInputTokens(inputTokens);
        }
        if (outputTokens != null) {
            tokenUsageTracker.addOutputTokens(outputTokens);
        }
    }

    private Long extractLong(Map<?, ?> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return null;
    }
}