package edu.adelaide.kali.config;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import java.time.Instant;

@Component
public class ServerClock {

    private volatile Instant startTime = Instant.now();

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        this.startTime = Instant.now();
    }

    public Instant getStartTime() {
        return startTime;
    }
}