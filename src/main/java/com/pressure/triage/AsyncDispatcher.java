package com.pressure.triage;

import com.pressure.model.Decision;
import com.pressure.model.WorkRequest;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional async pipeline that decouples request acceptance from work execution.
 * Useful when the work step is non-trivial and clients can tolerate eventual
 * delivery — the bounded queue protects against unbounded memory growth.
 *
 * Triage decisions are still made synchronously in the caller's thread (cheap).
 * Only the work step (and its release()) runs on the worker pool, drained
 * FIFO from a bounded queue.
 */
@Component
public class AsyncDispatcher {

    private final TriageEngine triage;
    private final LoadMonitor monitor;
    private final int workers;
    private final int queueCapacity;
    private final long shutdownTimeoutMs;
    private ThreadPoolExecutor pool;
    private final AtomicInteger seq = new AtomicInteger();
    private final AtomicInteger queued = new AtomicInteger();
    private final AtomicLong rejectedFull = new AtomicLong();

    public AsyncDispatcher(TriageEngine triage, LoadMonitor monitor, MeterRegistry meters,
                           @Value("${pressure.async.workers:4}") int workers,
                           @Value("${pressure.async.queue-capacity:256}") int queueCapacity,
                           @Value("${pressure.async.shutdown-timeout-ms:2000}") long shutdownTimeoutMs) {
        this.triage = triage;
        this.monitor = monitor;
        this.workers = Math.max(1, workers);
        this.queueCapacity = Math.max(1, queueCapacity);
        this.shutdownTimeoutMs = Math.max(0, shutdownTimeoutMs);
        meters.gauge("pressure.async.queue_size", queued);
    }

    @PostConstruct
    void start() {
        var bounded = new LinkedBlockingQueue<Runnable>(queueCapacity);
        this.pool = new ThreadPoolExecutor(workers, workers, 60, TimeUnit.SECONDS, bounded,
                r -> {
                    Thread t = new Thread(r, "pressure-async-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                });
        this.pool.allowCoreThreadTimeOut(false);
    }

    @PreDestroy
    void stop() {
        if (pool == null) return;
        pool.shutdown();
        try {
            if (!pool.awaitTermination(shutdownTimeoutMs, TimeUnit.MILLISECONDS)) pool.shutdownNow();
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            pool.shutdownNow();
        }
    }

    public CompletableFuture<AsyncResult> submit(WorkRequest req) {
        Decision decision = triage.triage(req);
        CompletableFuture<AsyncResult> result = new CompletableFuture<>();
        if (!decision.admit()) {
            // already rejected by triage; nothing to enqueue
            result.complete(new AsyncResult(decision, false, null));
            return result;
        }
        try {
            queued.incrementAndGet();
            pool.execute(() -> {
                queued.decrementAndGet();
                long start = System.nanoTime();
                Throwable err = null;
                try {
                    // task body — kept minimal here so the dispatcher remains content-agnostic.
                    // Production use would invoke a registered handler keyed by req.operation().
                    if (req.operation() == null) throw new IllegalStateException("missing operation");
                } catch (Throwable t) {
                    err = t;
                } finally {
                    monitor.workTimer().record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
                    triage.release();
                }
                if (err != null) result.completeExceptionally(err);
                else result.complete(new AsyncResult(decision, true, null));
            });
        } catch (RejectedExecutionException ree) {
            queued.decrementAndGet();
            // queue full -> release the reservation triage made and report shed
            triage.release();
            rejectedFull.incrementAndGet();
            result.complete(new AsyncResult(decision, false, "queue-full"));
        }
        return result;
    }

    public int queueSize() { return queued.get(); }
    public long rejectedFull() { return rejectedFull.get(); }
    public int activeWorkers() { return pool == null ? 0 : pool.getActiveCount(); }
    public int queueCapacity() { return queueCapacity; }

    public record AsyncResult(Decision decision, boolean executed, String rejectionReason) {}
}
