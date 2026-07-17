package com.dataease.migration.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public final class MigrationJob {
    private static final Logger LOGGER = LoggerFactory.getLogger(MigrationJob.class);

    private final List<String> logs = new CopyOnWriteArrayList<>();
    private final List<SseEmitter> subscribers = new CopyOnWriteArrayList<>();
    private final Instant createdAt = Instant.now();
    private boolean completed;

    public void log(String message) {
        String line = "[" + Instant.now() + "] " + message;
        logs.add(line);
        LOGGER.info(line);
        for (SseEmitter subscriber : subscribers) {
            send(subscriber, "log", line);
        }
    }

    public void subscribe(SseEmitter emitter) {
        logs.forEach(line -> send(emitter, "log", line));
        emitter.onCompletion(() -> subscribers.remove(emitter));
        emitter.onTimeout(() -> subscribers.remove(emitter));
        synchronized (this) {
            if (!completed) {
                subscribers.add(emitter);
                return;
            }
        }
        complete(emitter);
    }

    public void complete() {
        List<SseEmitter> activeSubscribers;
        synchronized (this) {
            if (completed) {
                return;
            }
            completed = true;
            activeSubscribers = new ArrayList<>(subscribers);
        }
        activeSubscribers.forEach(this::complete);
    }

    public Instant createdAt() {
        return createdAt;
    }

    private void send(SseEmitter emitter, String name, String data) {
        try {
            emitter.send(SseEmitter.event().name(name).data(data));
        } catch (IOException e) {
            subscribers.remove(emitter);
        }
    }

    private void complete(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().name("complete").data("迁移任务结束"));
        } catch (IOException ignored) {
            // The browser may have disconnected before the final event.
        } finally {
            emitter.complete();
        }
    }
}
