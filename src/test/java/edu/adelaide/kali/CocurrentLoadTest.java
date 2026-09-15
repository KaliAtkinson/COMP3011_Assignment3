package edu.adelaide.kali;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.concurrent.CopyOnWriteArrayList;
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

        HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .executor(Executors.newVirtualThreadPerTaskExecutor())
            .build();

        RestClient client = RestClient.builder()
            .requestFactory(new JdkClientHttpRequestFactory(httpClient))
            .build();

        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        CountDownLatch latch = new CountDownLatch(requestCount);
        AtomicInteger successCount = new AtomicInteger(0);
        CopyOnWriteArrayList<String> failures = new CopyOnWriteArrayList<>();

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
                    } else {
                        failures.add("Non-2xx status: " + response.getStatusCode());
                    }
                } catch (Exception ex) {
                    failures.add(ex.getClass().getSimpleName() + ": " + ex.getMessage()
                        + (ex.getCause() != null ? " | caused by: " + ex.getCause() : ""));
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean allCompleted = latch.await(30, TimeUnit.SECONDS);
        long elapsedMillis = (System.nanoTime() - startTime) / 1_000_000;
        executor.shutdown();

        if (!failures.isEmpty()) {
            System.out.println("Failure details (" + failures.size() + " total):");
            failures.forEach(System.out::println);
        }

        assertTrue(allCompleted, "Not all requests completed within the 30-second timeout");
        assertEquals(requestCount, successCount.get(), "Some requests failed or did not return 2xx");
        assertTrue(elapsedMillis < 10_000,
            "Requests took " + elapsedMillis + "ms — expected under 10 seconds for " + requestCount + " concurrent requests");
    }
}