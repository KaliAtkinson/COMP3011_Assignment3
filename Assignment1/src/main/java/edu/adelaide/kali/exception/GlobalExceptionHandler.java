package edu.adelaide.kali.exception;

import edu.adelaide.kali.dto.ErrorResponse;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.ZoneOffset;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private static final DateTimeFormatter MILLIS_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    @ExceptionHandler(ShutdownConflictException.class)
    public ResponseEntity<ErrorResponse> handleShutdownConflict(
            ShutdownConflictException ex, HttpServletRequest request) {

        ErrorResponse body = new ErrorResponse(
            MILLIS_FORMAT.format(Instant.now()),
            HttpStatus.CONFLICT.value(),
            "Conflict",
            ex.getMessage(),
            request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /**
     * Report upstream transcription problems without forwarding provider error bodies
     * or details that could contain secrets. The server logs safe HTTP status details.
     */
    @ExceptionHandler(TranscriptionException.class)
    public ResponseEntity<ErrorResponse> handleTranscriptionFailure(
            TranscriptionException ex, HttpServletRequest request) {
        logger.warn("Transcription unavailable on {}", request.getRequestURI());
        ErrorResponse body = new ErrorResponse(
            MILLIS_FORMAT.format(Instant.now()),
            HttpStatus.BAD_GATEWAY.value(),
            "Bad Gateway",
            "Transcription service unavailable. See server logs for details.",
            request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(
            Exception ex, HttpServletRequest request) {

        logger.error("Unhandled exception on {}", request.getRequestURI(), ex);

        ErrorResponse body = new ErrorResponse(
            MILLIS_FORMAT.format(Instant.now()),
            HttpStatus.INTERNAL_SERVER_ERROR.value(),
            "Internal Server Error",
            "An unexpected server error occurred.",
            request.getRequestURI()
        );
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}