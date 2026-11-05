package edu.adelaide.kali.dto;

public record GlobalStatsResponse(
    long inputTokens,
    long outputTokens
) {}