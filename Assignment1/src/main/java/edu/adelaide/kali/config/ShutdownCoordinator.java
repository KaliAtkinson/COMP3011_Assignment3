package edu.adelaide.kali.config;

import edu.adelaide.kali.exception.ShutdownConflictException;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.SpringApplication;
import org.springframework.stereotype.Component;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
public class ShutdownCoordinator {

    private final ApplicationContext context;
    private final AtomicBoolean shutdownInProgress = new AtomicBoolean(false);

    public ShutdownCoordinator(ApplicationContext context) {
        this.context = context;
    }

    public void requestShutdown() {
        if (!shutdownInProgress.compareAndSet(false, true)) {
            throw new ShutdownConflictException("Graceful shutdown is already in progress.");
        }

        Thread shutdownThread = new Thread(() -> {
            try {
                Thread.sleep(200); // let the HTTP response flush before tearing down
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            SpringApplication.exit(context, () -> 0);
            System.exit(0);
        });
        shutdownThread.start();
    }
}