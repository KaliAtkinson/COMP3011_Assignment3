package edu.adelaide.kali.web;

import edu.adelaide.kali.config.ServerClock;
import edu.adelaide.kali.config.ShutdownCoordinator;
import edu.adelaide.kali.exception.ShutdownConflictException;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AdminController.class)
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServerClock serverClock;

    @MockitoBean
    private ShutdownCoordinator shutdownCoordinator;

    @Test
    void uptimeEndpointReturnsCorrectShape() throws Exception {
        Instant fixedStart = Instant.now().minus(10, ChronoUnit.SECONDS);
        when(serverClock.getStartTime()).thenReturn(fixedStart);

        mockMvc.perform(get("/api/v1/admin/uptime"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.utcServerStart").exists())
            .andExpect(jsonPath("$.utcNow").exists())
            .andExpect(jsonPath("$.serverUptimeSeconds").value(org.hamcrest.Matchers.greaterThan(9.0)));
    }

    @Test
    void shutdownEndpointReturns202OnFirstRequest() throws Exception {
        mockMvc.perform(post("/api/v1/admin/shutdown"))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.message").value("Graceful shutdown requested."));
    }

    @Test
    void shutdownEndpointReturns409WhenAlreadyInProgress() throws Exception {
        doThrow(new ShutdownConflictException("Graceful shutdown is already in progress."))
            .when(shutdownCoordinator).requestShutdown();

        mockMvc.perform(post("/api/v1/admin/shutdown"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.status").value(409))
            .andExpect(jsonPath("$.error").value("Conflict"));
    }
}