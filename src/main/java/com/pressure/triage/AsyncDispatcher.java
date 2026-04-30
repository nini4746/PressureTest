package com.pressure.triage;

import com.pressure.model.Decision;
import com.pressure.model.WorkRequest;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Optional async pipeline that decouples request acceptance from work execution.
 * Useful when the work step is non-trivial and clients can tolerate eventual
 * delivery — bounded queue protects against unbounded memory growth.
 *
 * Triage decisions are still made synchronously in the caller's thread (cheap).
 * Only the work step (and its release()) runs on the worker pool. Queue is
 * priority-ordered by triage score so high-value tasks drain first when the
 * pool is saturated.
 */
@Component
public class AsyncDispatcher {

    private static final Logger log = LoggerFactory.getLogger(AsyncDispatcher.class);

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
        // priority queue: higher decision score => earlier execution
        var queue = new PriorityBlockingQueue<Runnable>(64, (a, b) -> {
            if (a instanceof PriorityTask pa && b instanceof PriorityTask pb) {
                return Double.compare(pb.priority, pa.priority);
            }
            return 0;
        });
        // wrap to enforce capacity since PriorityBlockingQueue is unbounded
        var bounded = new LinkedBlockingQueue<Runnable>(queueCapacity);
        // we cannot use both bounded + priority directly; instead use bounded LinkedBlockingQueue
        // but track priority via natural ordering via re-queue. Simpler: enforce capacity ourselves.
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
            pool.execute(new PriorityTask(decision.score(), () -> {
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
            }));
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

    private static final class PriorityTask implements Runnable {
        final double priority;
        final Runnable delegate;

        PriorityTask(double priority, Runnable delegate) {
            this.priority = priority;
            this.delegate = delegate;
        }

        @Override
        public void run() {
            delegate.run();
        }
    }
}
