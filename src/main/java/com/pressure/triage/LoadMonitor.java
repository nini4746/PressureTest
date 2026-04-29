package com.pressure.triage;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class LoadMonitor {

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong totalAdmitted = new AtomicLong();
    private final AtomicLong totalShed = new AtomicLong();
    private final AtomicLong totalDegraded = new AtomicLong();

    private final Counter admitCounter;
    private final Counter shedCounter;
    private final Counter degradeCounter;
    private final Timer decisionTimer;
    private final Timer workTimer;

    public LoadMonitor(MeterRegistry meters) {
        this.admitCounter = Counter.builder("pressure.admitted").register(meters);
        this.shedCounter = Counter.builder("pressure.shed").register(meters);
        this.degradeCounter = Counter.builder("pressure.degraded").register(meters);
        this.decisionTimer = Timer.builder("pressure.decision.latency")
                .publishPercentiles(0.5, 0.95, 0.99).register(meters);
        this.workTimer = Timer.builder("pressure.work.latency")
                .publishPercentiles(0.5, 0.95, 0.99).register(meters);
        meters.gauge("pressure.in_flight", inFlight);
    }

    public int currentInFlight() { return inFlight.get(); }
    public long totalAdmitted() { return totalAdmitted.get(); }
    public long totalShed() { return totalShed.get(); }
    public long totalDegraded() { return totalDegraded.get(); }

    public boolean tryReserve(int expected) {
        return inFlight.compareAndSet(expected, expected + 1);
    }

    public void onAdmit() {
        totalAdmitted.incrementAndGet();
        admitCounter.increment();
    }

    public void onDegraded() {
        totalDegraded.incrementAndGet();
        degradeCounter.increment();
    }

    public void onShed() {
        totalShed.incrementAndGet();
        shedCounter.increment();
    }

    public void onComplete() {
        inFlight.decrementAndGet();
    }

    public Timer decisionTimer() { return decisionTimer; }
    public Timer workTimer() { return workTimer; }

    public void reset() {
        inFlight.set(0);
        totalAdmitted.set(0);
        totalShed.set(0);
        totalDegraded.set(0);
    }
}
