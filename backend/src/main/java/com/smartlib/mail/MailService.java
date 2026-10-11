package com.smartlib.mail;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import jakarta.annotation.PreDestroy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.smartlib.common.config.AppProperties;
import com.smartlib.common.util.Texts;

/**
 * Two delivery paths:
 * <ul>
 *   <li>{@link #sendNow} for emails carrying one-time secrets (OTP, links): sent directly, never stored.</li>
 *   <li>{@link #enqueue} for ordinary notifications: stored in the outbox and delivered with retries.</li>
 * </ul>
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);

    private final MailTransport transport;
    private final EmailOutboxRepository outbox;
    private final TransactionTemplate tx;
    private final boolean async;
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicBoolean draining = new AtomicBoolean(false);

    public MailService(MailTransport transport, EmailOutboxRepository outbox, TransactionTemplate tx, AppProperties properties) {
        this.transport = transport;
        this.outbox = outbox;
        this.tx = tx;
        this.async = properties.mail().async();
    }

    public void sendNow(MailMessage message) {
        Runnable task = () -> {
            for (int attempt = 1; attempt <= 3; attempt++) {
                try {
                    transport.send(message);
                    return;
                } catch (Exception e) {
                    log.warn("Email to {} failed (attempt {}/3): {}", mask(message.to()), attempt, e.getMessage());
                    sleep(attempt * 1000L);
                }
            }
        };
        if (async) {
            executor.submit(task);
        } else {
            task.run();
        }
    }

    public void enqueue(MailMessage message) {
        EmailOutbox row = new EmailOutbox();
        row.setToEmail(message.to());
        row.setSubject(Texts.truncate(message.subject(), 255));
        row.setHtmlBody(message.html());
        row.setTextBody(message.text());
        row.setStatus("PENDING");
        row.setNextAttemptAt(Instant.now());
        row.setCreatedAt(Instant.now());
        outbox.save(row);
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    kick();
                }
            });
        } else {
            kick();
        }
    }

    public void kick() {
        if (async) {
            executor.submit(this::drainOutbox);
        } else {
            drainOutbox();
        }
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 10_000)
    public void scheduledDrain() {
        drainOutbox();
    }

    public void drainOutbox() {
        if (!draining.compareAndSet(false, true)) {
            return;
        }
        try {
            for (int round = 0; round < 10; round++) {
                var due = tx.execute(s -> outbox.findDue(Instant.now(), PageRequest.of(0, 25)));
                if (due == null || due.isEmpty()) {
                    return;
                }
                for (EmailOutbox row : due) {
                    deliver(row);
                }
            }
        } finally {
            draining.set(false);
        }
    }

    private void deliver(EmailOutbox row) {
        String error = null;
        try {
            transport.send(new MailMessage(row.getToEmail(), row.getSubject(), row.getHtmlBody(), row.getTextBody()));
        } catch (Exception e) {
            error = Texts.truncate(e.getClass().getSimpleName() + ": " + e.getMessage(), 500);
        }
        String finalError = error;
        tx.executeWithoutResult(s -> outbox.findById(row.getId()).ifPresent(r -> {
            r.setAttempts(r.getAttempts() + 1);
            if (finalError == null) {
                r.setStatus("SENT");
                r.setSentAt(Instant.now());
                r.setLastError(null);
            } else {
                r.setLastError(finalError);
                if (r.getAttempts() >= 5) {
                    r.setStatus("FAILED");
                } else {
                    r.setNextAttemptAt(Instant.now().plus(Duration.ofSeconds(30L * (1L << r.getAttempts()))));
                }
            }
        }));
    }

    @PreDestroy
    void shutdown() {
        executor.shutdown();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    static String mask(String email) {
        int at = email == null ? -1 : email.indexOf('@');
        return at <= 1 ? "***" : email.charAt(0) + "***" + email.substring(at);
    }
}
