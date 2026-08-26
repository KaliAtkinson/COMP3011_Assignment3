package edu.adelaide.kali.config;

import org.springframework.stereotype.Component;
import java.lang.management.ManagementFactory;
import java.time.Instant;

@Component
public class ServerClock {

    private final Instant startTime;

    public ServerClock() {
        this.startTime = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());
    }

    public Instant getStartTime() {
        return startTime;
    }
}