package edu.adelaide.kali.dto;

public record ErrorResponse(
    String timestamp,
    int status,
    String error,
    String message,
    String path
) {}