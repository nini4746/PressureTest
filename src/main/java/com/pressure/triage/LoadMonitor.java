package com.pressure.triage;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class LoadMonitor {

    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicLong totalAdmitted = new AtomicLong();
    private final AtomicLong totalShed = new AtomicLong();
    private final AtomicLong totalDegraded = new AtomicLong();

    public int currentInFlight() { return inFlight.get(); }
    public long totalAdmitted() { return totalAdmitted.get(); }
    public long totalShed() { return totalShed.get(); }
    public long totalDegraded() { return totalDegraded.get(); }

    public void onAdmit() {
        inFlight.incrementAndGet();
        totalAdmitted.incrementAndGet();
    }

    public void onDegraded() {
        totalDegraded.incrementAndGet();
    }

    public void onShed() {
        totalShed.incrementAndGet();
    }

    public void onComplete() {
        inFlight.decrementAndGet();
    }

    public void reset() {
        inFlight.set(0);
        totalAdmitted.set(0);
        totalShed.set(0);
        totalDegraded.set(0);
    }
}
