package com.pressure.triage;

import com.pressure.model.WorkRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
public class ScoreCalculator {

    private final double tierWeight;
    private final double costPenalty;
    private final double loadPenalty;
    private final int budget;

    public ScoreCalculator(@Value("${pressure.score.tier-weight:30.0}") double tierWeight,
                           @Value("${pressure.score.cost-penalty:1.0}") double costPenalty,
                           @Value("${pressure.score.load-penalty:50.0}") double loadPenalty,
                           @Value("${pressure.budget:8}") int budget) {
        this.tierWeight = tierWeight;
        this.costPenalty = costPenalty;
        this.loadPenalty = loadPenalty;
        this.budget = budget;
    }

    public double score(WorkRequest req, int currentInFlight) {
        double tierComponent = req.tier().weight() * tierWeight;
        double costComponent = -costPenalty * Math.max(1, req.costUnits());
        double loadFraction = Math.min(1.0, currentInFlight / (double) Math.max(1, budget));
        double loadComponent = -loadPenalty * loadFraction;
        return tierComponent + costComponent + loadComponent;
    }
}
