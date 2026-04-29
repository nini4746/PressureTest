package com.pressure.triage;

import com.pressure.model.Decision;
import com.pressure.model.WorkRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

@Service
public class TriageEngine {

    private static final int MAX_CAS_RETRIES = 16;

    private final LoadMonitor monitor;
    private final AdmissionPolicy policy;
    private final int budget;
    private final double admitThreshold;
    private final double degradeThreshold;

    public TriageEngine(LoadMonitor monitor, AdmissionPolicy policy,
                        @Value("${pressure.budget:8}") int budget,
                        @Value("${pressure.threshold.admit:30.0}") double admitThreshold,
                        @Value("${pressure.threshold.degrade:20.0}") double degradeThreshold) {
        this.monitor = monitor;
        this.policy = policy;
        this.budget = budget;
        this.admitThreshold = admitThreshold;
        this.degradeThreshold = degradeThreshold;
    }

    public Decision triage(WorkRequest req) {
        long start = System.nanoTime();
        try {
            return decide(req);
        } finally {
            monitor.decisionTimer().record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
        }
    }

    private Decision decide(WorkRequest req) {
        for (int attempt = 0; attempt < MAX_CAS_RETRIES; attempt++) {
            int inFlight = monitor.currentInFlight();
            double score = policy.score(req, inFlight);
            DecisionKind kind = classify(inFlight, score);
            if (kind == DecisionKind.SHED) {
                monitor.onShed();
                return Decision.shed(score, "load=" + inFlight + " score=" + String.format("%.1f", score));
            }
            if (!monitor.tryReserve(inFlight)) {
                continue;
            }
            monitor.onAdmit();
            if (kind == DecisionKind.DEGRADED) {
                monitor.onDegraded();
                return Decision.degraded(score);
            }
            return Decision.admit(score);
        }
        // contention exhausted: shed gracefully
        int inFlight = monitor.currentInFlight();
        double score = policy.score(req, inFlight);
        monitor.onShed();
        return Decision.shed(score, "contention");
    }

    private DecisionKind classify(int inFlight, double score) {
        if (inFlight < budget) return DecisionKind.ADMIT;
        if (score >= admitThreshold) return DecisionKind.ADMIT;
        if (score >= degradeThreshold) return DecisionKind.DEGRADED;
        return DecisionKind.SHED;
    }

    public void release() {
        monitor.onComplete();
    }

    private enum DecisionKind { ADMIT, DEGRADED, SHED }
}
