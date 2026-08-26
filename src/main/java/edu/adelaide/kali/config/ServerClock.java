package edu.adelaide.kali.config;

import org.springframework.stereotype.Component;
import java.time.Instant;

@Component
public class ServerClock {

    private final Instant startTime;

    public ServerClock() {
        this.startTime = Instant.now();
    }

    public Instant getStartTime() {
        return startTime;
    }
}