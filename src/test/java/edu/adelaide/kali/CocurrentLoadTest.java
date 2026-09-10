package edu.adelaide.kali;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrentLoadTest {

    @LocalServerPort
    private int port;

    @Test
    void handlesOver200ConcurrentRequestsWithoutErrors() throws InterruptedException {
        int requestCount = 250;
        RestClient client = RestClient.create();
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(requestCount);
        AtomicInteger successCount = new AtomicInteger(0);

        long startTime = System.nanoTime();

        for (int i = 0; i < requestCount; i++) {
            executor.submit(() -> {
                try {
                    ResponseEntity<String> response = client.get()
                        .uri("http://localhost:" + port + "/api/v1/admin/uptime")
                        .retrieve()
                        .toEntity(String.class);

                    if (response.getStatusCode().is2xxSuccessful()) {
                        successCount.incrementAndGet();
                    }
                } catch (Exception ex) {
                    // The assertion below checks total successes, so any failure surfaces there.
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean allCompleted = latch.await(30, TimeUnit.SECONDS);
        long elapsedMillis = (System.nanoTime() - startTime) / 1_000_000;
        executor.shutdown();

        assertTrue(allCompleted, "Not all requests completed within the 30-second timeout");
        assertEquals(requestCount, successCount.get(), "Some requests failed or did not return 2xx");
        assertTrue(elapsedMillis < 10_000,
            "Requests took " + elapsedMillis + "ms — expected under 10 seconds for " + requestCount + " concurrent requests");
    }
}