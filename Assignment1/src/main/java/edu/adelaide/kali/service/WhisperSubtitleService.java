package edu.adelaide.kali.service;

import edu.adelaide.kali.config.AudioUsageTracker;
import edu.adelaide.kali.config.OpenAiProperties;
import edu.adelaide.kali.dto.SubtitleResponse;
import edu.adelaide.kali.exception.TranscriptionException;
import edu.adelaide.kali.subtitle.WhisperSubtitleMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * Uses the required Whisper model and returns timestamps, rather than the
 * plain text provided by the Assignment 1 transcription service.
 *
 * AI Assistance: Developed with guidance and code assistance from OpenAI ChatGPT.
 */
@Service
@Profile("!local")
public class WhisperSubtitleService implements SubtitleService {
    private final RestClient restClient;
    private final OpenAiProperties properties;
    private final AudioUsageTracker usageTracker;

    public WhisperSubtitleService(RestClient restClient, OpenAiProperties properties,
                                  AudioUsageTracker usageTracker) {
        this.restClient = restClient;
        this.properties = properties;
        this.usageTracker = usageTracker;
    }

    @Override
    public SubtitleResponse transcribe(MultipartFile audioFile) {
        if (audioFile == null || audioFile.isEmpty()) {
            throw new IllegalArgumentException("Audio file must not be empty");
        }
        try {
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("file", audioFile.getResource());
            body.add("model", "whisper-1");
            body.add("response_format", "verbose_json");
            body.add("timestamp_granularities[]", "word");
            body.add("timestamp_granularities[]", "segment");

            Map<?, ?> response = restClient.post()
                    .uri(properties.url())
                    .header("Authorization", "Bearer " + properties.key())
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(body)
                    .retrieve()
                    .body(Map.class);

            if (response == null || !(response.get("text") instanceof String)) {
                throw new TranscriptionException("Whisper returned an invalid transcription response");
            }
            SubtitleResponse subtitles = WhisperSubtitleMapper.fromVerboseJson(response);
            // Count successful cloud calls, not failed uploads or local mock requests.
            usageTracker.addSeconds(reportedUsageSeconds(response, subtitles.duration()));
            return subtitles;
        } catch (TranscriptionException ex) {
            throw ex;
        } catch (Exception ex) {
            // Avoid leaking API headers, keys, or the uploaded audio in messages.
            throw new TranscriptionException("Unable to retrieve subtitles from Whisper");
        }
    }

    static double reportedUsageSeconds(Map<?, ?> response, double fallbackDuration) {
        if (response.get("usage") instanceof Map<?, ?> usage
                && usage.get("seconds") instanceof Number number) {
            double seconds = number.doubleValue();
            if (Double.isFinite(seconds) && seconds >= 0) {
                return seconds;
            }
        }
        // Some Whisper verbose_json responses give duration but no usage field.
        return fallbackDuration;
    }
}
