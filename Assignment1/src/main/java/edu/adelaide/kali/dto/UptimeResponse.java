package edu.adelaide.kali.dto;

public record UptimeResponse(
    String utcServerStart,
    String utcNow,
    double serverUptimeSeconds
) {}