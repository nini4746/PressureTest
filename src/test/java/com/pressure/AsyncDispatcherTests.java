package com.pressure;

import com.pressure.model.UserTier;
import com.pressure.model.WorkRequest;
import com.pressure.triage.AsyncDispatcher;
import com.pressure.triage.DynamicThresholdProvider;
import com.pressure.triage.LoadMonitor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class AsyncDispatcherTests {

    @Autowired private AsyncDispatcher dispatcher;
    @Autowired private LoadMonitor monitor;
    @Autowired private DynamicThresholdProvider thresholds;

    @BeforeEach
    void reset() {
        monitor.reset();
        thresholds.resetState();
    }

    private WorkRequest req(UserTier tier, int cost) {
        return new WorkRequest("u", tier, cost, "op-" + System.nanoTime());
    }

    @Test
    void singleRequestExecutesAndReleases() throws Exception {
        AsyncDispatcher.AsyncResult r = dispatcher.submit(req(UserTier.PREMIUM, 1))
                .get(5, TimeUnit.SECONDS);
        assertTrue(r.decision().admit());
        assertTrue(r.executed());
        assertEquals(0, monitor.currentInFlight(), "release must have run");
    }

    @Test
    void shedDecisionDoesNotEnqueue() throws Exception {
        // hold the budget by directly calling triage so in-flight stays high
        com.pressure.triage.TriageEngine engine = applicationContext.getBean(com.pressure.triage.TriageEngine.class);
        int held = 0;
        for (int i = 0; i < 8; i++) {
            var d = engine.triage(req(UserTier.STANDARD, 1));
            if (d.admit()) held++;
        }
        try {
            // FREE high-cost should be shed by triage now
            AsyncDispatcher.AsyncResult r = dispatcher.submit(req(UserTier.FREE, 500))
                    .get(2, TimeUnit.SECONDS);
            assertFalse(r.decision().admit(), "FREE high-cost under saturation must be shed");
            assertFalse(r.executed());
        } finally {
            for (int i = 0; i < held; i++) engine.release();
        }
    }

    @org.springframework.beans.factory.annotation.Autowired
    private org.springframework.context.ApplicationContext applicationContext;

    @Test
    void multipleSubmissionsCompleteCorrectly() throws Exception {
        List<CompletableFuture<AsyncDispatcher.AsyncResult>> futures = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            futures.add(dispatcher.submit(req(UserTier.PREMIUM, 1)));
        }
        for (var f : futures) {
            AsyncDispatcher.AsyncResult r = f.get(5, TimeUnit.SECONDS);
            // most should be admitted; we don't assert all because the budget might force some
            assertNotNull(r.decision());
        }
        assertEquals(0, monitor.currentInFlight(), "all admits must be released");
    }

    @Test
    void submissionsExposeQueueAndStats() throws Exception {
        dispatcher.submit(req(UserTier.PREMIUM, 1)).get(5, TimeUnit.SECONDS);
        // queueSize counts pending tasks; after .get() it must be 0
        assertEquals(0, dispatcher.queueSize());
        assertTrue(dispatcher.queueCapacity() > 0);
    }
}
