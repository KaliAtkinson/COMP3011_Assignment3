package edu.adelaide.kali.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "openai.api")
public record OpenAiProperties(String url, String model, String key) {}