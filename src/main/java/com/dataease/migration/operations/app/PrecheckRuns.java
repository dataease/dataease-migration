package com.dataease.migration.operations.app;

import com.dataease.migration.operations.core.OperationsApiException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Component
public final class PrecheckRuns {
    private static final Logger LOG = LoggerFactory.getLogger(PrecheckRuns.class);
    private static final Duration RETENTION = Duration.ofMinutes(10);
    private final OperationsService operations;
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final ExecutorService worker = new ThreadPoolExecutor(
            2, 2, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
            runnable -> {
                Thread thread = new Thread(runnable, "operations-precheck");
                thread.setDaemon(true);
                return thread;
            });

    public PrecheckRuns(OperationsService operations) {
        this.operations = operations;
    }

    public synchronized Map<String, Object> start(String owner, Map<String, Object> command) {
        return start(owner, command, false);
    }

    public synchronized Map<String, Object> start(String owner, Map<String, Object> command, boolean submit) {
        cleanExpired();
        if (runs.size() >= 128) throw busy();
        Run run = new Run(owner);
        runs.put(run.id, run);
        try {
            worker.execute(() -> execute(run, command, submit));
        } catch (RejectedExecutionException rejected) {
            runs.remove(run.id);
            throw busy();
        }
        return operations.publicRecord(run.snapshot());
    }

    public Map<String, Object> snapshot(String owner, String id) {
        Run run = runs.get(id);
        if (run == null || !run.owner.equals(owner) || run.expired(Instant.now()))
            throw OperationsApiException.notFound("预检查记录已失效，请重新检查。");
        return operations.publicRecord(run.snapshot());
    }

    private void execute(Run run, Map<String, Object> command, boolean submit) {
        try {
            Map<String, Object> result = submit
                    ? operations.checkAndSubmit(command, run::event)
                    : operations.precheck(command, run::event);
            run.finish(submit ? "submission" : "precheck", result);
        } catch (OperationsApiException failure) {
            run.fail(failure.getMessage());
        } catch (Exception failure) {
            LOG.warn("Precheck failed ({})", failure.getClass().getSimpleName());
            run.fail("预检查未完成，请核对配置后重新检查。");
        }
    }

    @Scheduled(fixedDelay = 60_000)
    public void cleanExpired() {
        Instant now = Instant.now();
        runs.values().removeIf(run -> run.expired(now));
    }

    @PreDestroy
    public void close() {
        worker.shutdownNow();
    }

    private static OperationsApiException busy() {
        return new OperationsApiException(HttpStatus.TOO_MANY_REQUESTS, "预检查任务较多，请稍后重试。");
    }

    private static final class Run {
        private final String id = UUID.randomUUID().toString();
        private final String owner;
        private final List<Map<?, ?>> checks = new ArrayList<>();
        private String state = "queued", current = "", error = "";
        private String resultKey;
        private Map<String, Object> result;
        private Instant expires;

        private Run(String owner) {
            this.owner = owner;
        }

        private synchronized void event(Map<String, Object> event) {
            if ("submitting".equals(event.get("type"))) {
                state = "submitting";
                current = "";
                return;
            }
            state = "running";
            if ("running".equals(event.get("type"))) current = (String) event.get("title");
            else if (event.get("check") instanceof Map<?, ?> check) {
                checks.add(check);
                current = "";
            }
        }

        private synchronized void finish(String key, Map<String, Object> result) {
            resultKey = key;
            this.result = result;
            state = "completed";
            current = "";
            expires = Instant.now().plus(RETENTION);
        }

        private synchronized void fail(String message) {
            state = "failed";
            current = "";
            error = message;
            expires = Instant.now().plus(RETENTION);
        }

        private synchronized boolean expired(Instant now) {
            return expires != null && !now.isBefore(expires);
        }

        private synchronized Map<String, Object> snapshot() {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            snapshot.put("id", id);
            snapshot.put("state", state);
            snapshot.put("current", current);
            snapshot.put("checks", List.copyOf(checks));
            if (result != null) snapshot.put(resultKey, result);
            if (!error.isBlank()) snapshot.put("error", error);
            return snapshot;
        }
    }
}
