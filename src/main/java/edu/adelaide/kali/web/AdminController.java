package edu.adelaide.kali.web;

import edu.adelaide.kali.config.ServerClock;
import edu.adelaide.kali.dto.UptimeResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;

@RestController
public class AdminController {

    private static final DateTimeFormatter MILLIS_FORMAT =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                          .withZone(java.time.ZoneOffset.UTC);

    private final ServerClock serverClock;

    public AdminController(ServerClock serverClock) {
        this.serverClock = serverClock;
    }

    @GetMapping("/api/v1/admin/uptime")
    public UptimeResponse getUptime() {
        Instant start = serverClock.getStartTime();
        Instant now = Instant.now();
        double uptimeSeconds = Duration.between(start, now).toNanos() / 1_000_000_000.0;

        return new UptimeResponse(
            MILLIS_FORMAT.format(start),
            MILLIS_FORMAT.format(now),
            uptimeSeconds
        );
    }
}