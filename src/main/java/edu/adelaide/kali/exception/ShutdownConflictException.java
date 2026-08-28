package edu.adelaide.kali.exception;

public class ShutdownConflictException extends RuntimeException {
    public ShutdownConflictException(String message) {
        super(message);
    }
}