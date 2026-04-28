package com.pressure.triage;

import com.pressure.model.Decision;
import com.pressure.model.WorkRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class TriageEngine {

    private final LoadMonitor monitor;
    private final ScoreCalculator scorer;
    private final int budget;
    private final double admitThreshold;
    private final double degradeThreshold;

    public TriageEngine(LoadMonitor monitor, ScoreCalculator scorer,
                        @Value("${pressure.budget:8}") int budget,
                        @Value("${pressure.threshold.admit:30.0}") double admitThreshold,
                        @Value("${pressure.threshold.degrade:20.0}") double degradeThreshold) {
        this.monitor = monitor;
        this.scorer = scorer;
        this.budget = budget;
        this.admitThreshold = admitThreshold;
        this.degradeThreshold = degradeThreshold;
    }

    public Decision triage(WorkRequest req) {
        int inFlight = monitor.currentInFlight();
        double score = scorer.score(req, inFlight);

        if (inFlight < budget) {
            monitor.onAdmit();
            return Decision.admit(score);
        }

        if (score >= admitThreshold) {
            monitor.onAdmit();
            return Decision.admit(score);
        }
        if (score >= degradeThreshold) {
            monitor.onAdmit();
            monitor.onDegraded();
            return Decision.degraded(score);
        }
        monitor.onShed();
        return Decision.shed(score, "load=" + inFlight + " score=" + String.format("%.1f", score));
    }

    public void release() {
        monitor.onComplete();
    }
}
