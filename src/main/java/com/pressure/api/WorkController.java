package com.pressure.api;

import com.pressure.model.Decision;
import com.pressure.model.UserTier;
import com.pressure.model.WorkRequest;
import com.pressure.triage.LoadMonitor;
import com.pressure.triage.TriageEngine;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class WorkController {

    private final TriageEngine triage;
    private final LoadMonitor monitor;

    public WorkController(TriageEngine triage, LoadMonitor monitor) {
        this.triage = triage;
        this.monitor = monitor;
    }

    public record WorkBody(String userId, String tier, int costUnits, String operation) {}

    @PostMapping("/work")
    public ResponseEntity<Map<String, Object>> work(@RequestBody WorkBody body) {
        UserTier tier;
        try {
            tier = UserTier.valueOf(body.tier().toUpperCase());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid tier"));
        }
        WorkRequest req = new WorkRequest(body.userId(), tier, body.costUnits(), body.operation());
        Decision d = triage.triage(req);
        if (!d.admit()) {
            Map<String, Object> retry = new LinkedHashMap<>();
            retry.put("admitted", false);
            retry.put("score", round(d.score()));
            retry.put("reason", d.reason());
            retry.put("retryAfterMs", 200);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(retry);
        }
        try {
            return ResponseEntity.ok(executeWork(req, d));
        } finally {
            triage.release();
        }
    }

    private Map<String, Object> executeWork(WorkRequest req, Decision d) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("admitted", true);
        body.put("score", round(d.score()));
        body.put("degraded", d.degraded());
        if (d.degraded()) {
            body.put("result", "minimal");
            body.put("notice", "served with reduced features under load");
        } else {
            body.put("result", "ok");
            body.put("operation", req.operation());
        }
        return body;
    }

    @GetMapping("/load")
    public Map<String, Object> load() {
        return Map.of(
                "inFlight", monitor.currentInFlight(),
                "admitted", monitor.totalAdmitted(),
                "degraded", monitor.totalDegraded(),
                "shed", monitor.totalShed()
        );
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
