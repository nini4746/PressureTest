package com.pressure.api;

import com.pressure.model.UserTier;
import com.pressure.model.WorkRequest;
import com.pressure.triage.AsyncDispatcher;
import com.pressure.triage.DynamicThresholdProvider;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RestController
@RequestMapping("/admin")
public class AdminController {

    private final DynamicThresholdProvider thresholds;
    private final AsyncDispatcher async;

    public AdminController(DynamicThresholdProvider thresholds, AsyncDispatcher async) {
        this.thresholds = thresholds;
        this.async = async;
    }

    @GetMapping("/threshold")
    public Map<String, Object> threshold() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("admit", thresholds.admitThreshold());
        out.put("admitBase", thresholds.admitBase());
        out.put("degrade", thresholds.degradeThreshold());
        out.put("degradeBase", thresholds.degradeBase());
        out.put("shedRateEma", thresholds.shedRateEma());
        out.put("loadRateEma", thresholds.loadRateEma());
        return out;
    }

    @PostMapping("/threshold/override")
    public Map<String, Object> override(@RequestParam(required = false) Double admit,
                                        @RequestParam(required = false) Double degrade) {
        if (admit != null) thresholds.overrideAdmit(admit);
        if (degrade != null) thresholds.overrideDegrade(degrade);
        return threshold();
    }

    @DeleteMapping("/threshold/override")
    public Map<String, Object> clear() {
        thresholds.clearOverrides();
        return threshold();
    }

    @GetMapping("/async")
    public Map<String, Object> asyncStats() {
        return Map.of(
                "queueSize", async.queueSize(),
                "queueCapacity", async.queueCapacity(),
                "activeWorkers", async.activeWorkers(),
                "rejectedFull", async.rejectedFull()
        );
    }

    @PostMapping("/async/work")
    public ResponseEntity<Map<String, Object>> submitAsync(@Valid @RequestBody AsyncBody body) {
        UserTier tier;
        try {
            tier = UserTier.valueOf(body.tier().toUpperCase());
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid tier"));
        }
        WorkRequest req = new WorkRequest(body.userId(), tier, body.costUnits(), body.operation());
        try {
            // 5s ceiling — protects callers from queue starvation. Production code would expose
            // a handle so the request can be polled rather than blocked on.
            AsyncDispatcher.AsyncResult r = async.submit(req).get(5, TimeUnit.SECONDS);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("admitted", r.decision().admit());
            resp.put("executed", r.executed());
            resp.put("score", r.decision().score());
            resp.put("rejectionReason", r.rejectionReason());
            HttpStatus status = r.executed() ? HttpStatus.OK
                    : (r.decision().admit() ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.SERVICE_UNAVAILABLE);
            return ResponseEntity.status(status).body(resp);
        } catch (TimeoutException te) {
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                    .body(Map.of("error", "async timeout"));
        } catch (ExecutionException | InterruptedException ee) {
            if (ee instanceof InterruptedException) Thread.currentThread().interrupt();
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "async failure", "message", ee.getMessage()));
        }
    }

    public record AsyncBody(@NotBlank String userId,
                            @NotBlank String tier,
                            @Min(0) int costUnits,
                            @NotBlank String operation) {}
}
