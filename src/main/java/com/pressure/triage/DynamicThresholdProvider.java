package com.pressure.triage;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Adaptive thresholds for {@link TriageEngine}. Tracks an EMA of the shed-vs-decided
 * ratio and (optionally) a load-vs-budget ratio, and outputs the current
 * {@code admitThreshold} / {@code degradeThreshold} as a function of those signals.
 *
 * Strategy: when shed rate is high, the system is over budget — make it harder to
 * admit borderline traffic by raising both thresholds. When the shed rate is low
 * we relax them back toward the configured base values. Both outputs are clamped
 * within configured bounds.
 *
 * A manual override pair is provided to lock the thresholds (e.g., during a known
 * load test or maintenance period).
 */
@Component
public class DynamicThresholdProvider {

    private final double admitBase;
    private final double admitMin;
    private final double admitMax;
    private final double degradeBase;
    private final double degradeMin;
    private final double degradeMax;
    private final double alpha;
    private final double sensitivity;

    private final AtomicLong shedEmaBits = new AtomicLong(Double.doubleToRawLongBits(0.0));
    private final AtomicLong loadEmaBits = new AtomicLong(Double.doubleToRawLongBits(0.0));
    private volatile Double admitOverride;
    private volatile Double degradeOverride;

    public DynamicThresholdProvider(MeterRegistry meters,
                                    @Value("${pressure.threshold.admit:30.0}") double admitBase,
                                    @Value("${pressure.threshold.admit.min:20.0}") double admitMin,
                                    @Value("${pressure.threshold.admit.max:60.0}") double admitMax,
                                    @Value("${pressure.threshold.degrade:20.0}") double degradeBase,
                                    @Value("${pressure.threshold.degrade.min:10.0}") double degradeMin,
                                    @Value("${pressure.threshold.degrade.max:40.0}") double degradeMax,
                                    @Value("${pressure.threshold.ema-alpha:0.05}") double alpha,
                                    @Value("${pressure.threshold.sensitivity:60.0}") double sensitivity) {
        if (admitMin > admitBase || admitBase > admitMax)
            throw new IllegalArgumentException("admit threshold bounds invalid");
        if (degradeMin > degradeBase || degradeBase > degradeMax)
            throw new IllegalArgumentException("degrade threshold bounds invalid");
        if (degradeBase > admitBase)
            throw new IllegalArgumentException("degrade base must be <= admit base");
        this.admitBase = admitBase;
        this.admitMin = admitMin;
        this.admitMax = admitMax;
        this.degradeBase = degradeBase;
        this.degradeMin = degradeMin;
        this.degradeMax = degradeMax;
        this.alpha = Math.min(1.0, Math.max(0.0, alpha));
        this.sensitivity = Math.max(0.0, sensitivity);
        meters.gauge("pressure.threshold.admit", this, DynamicThresholdProvider::admitThreshold);
        meters.gauge("pressure.threshold.degrade", this, DynamicThresholdProvider::degradeThreshold);
        meters.gauge("pressure.threshold.shed_ema", this, DynamicThresholdProvider::shedRateEma);
        meters.gauge("pressure.threshold.load_ema", this, DynamicThresholdProvider::loadRateEma);
    }

    public double admitThreshold() {
        Double o = admitOverride;
        if (o != null) return o;
        // shedRateEma in [0,1]; shift up by sensitivity * ema. when no shed observed,
        // threshold stays at base. could also subtract a small term to relax under
        // sustained admit, but that risks oscillation; keep monotonic-up for safety.
        double shift = sensitivity * shedRateEma();
        double next = admitBase + shift;
        return Math.max(admitMin, Math.min(admitMax, next));
    }

    public double degradeThreshold() {
        Double o = degradeOverride;
        if (o != null) return o;
        double shift = (sensitivity / 2.0) * shedRateEma();
        double next = degradeBase + shift;
        // ensure degrade < admit always
        return Math.max(degradeMin, Math.min(Math.min(degradeMax, admitThreshold() - 1.0), next));
    }

    public void recordOutcome(boolean shed) {
        update(shedEmaBits, shed ? 1.0 : 0.0);
    }

    public void recordLoadFraction(double inFlightFraction) {
        update(loadEmaBits, Math.max(0.0, Math.min(1.0, inFlightFraction)));
    }

    private void update(AtomicLong holder, double sample) {
        while (true) {
            long bits = holder.get();
            double prev = Double.longBitsToDouble(bits);
            double next = alpha * sample + (1 - alpha) * prev;
            if (holder.compareAndSet(bits, Double.doubleToRawLongBits(next))) return;
        }
    }

    public double shedRateEma() { return Double.longBitsToDouble(shedEmaBits.get()); }
    public double loadRateEma() { return Double.longBitsToDouble(loadEmaBits.get()); }

    public void overrideAdmit(Double v) { this.admitOverride = v; }
    public void overrideDegrade(Double v) { this.degradeOverride = v; }
    public void clearOverrides() {
        this.admitOverride = null;
        this.degradeOverride = null;
    }

    public void resetState() {
        clearOverrides();
        shedEmaBits.set(Double.doubleToRawLongBits(0.0));
        loadEmaBits.set(Double.doubleToRawLongBits(0.0));
    }

    public double admitBase() { return admitBase; }
    public double degradeBase() { return degradeBase; }
}
