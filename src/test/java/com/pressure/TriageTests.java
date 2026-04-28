package com.pressure;

import com.pressure.model.Decision;
import com.pressure.model.UserTier;
import com.pressure.model.WorkRequest;
import com.pressure.triage.LoadMonitor;
import com.pressure.triage.ScoreCalculator;
import com.pressure.triage.TriageEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class TriageTests {

	@Autowired private TriageEngine engine;
	@Autowired private LoadMonitor monitor;
	@Autowired private ScoreCalculator scorer;

	@BeforeEach
	void resetState() {
		monitor.reset();
	}

	private WorkRequest req(UserTier tier, int cost) {
		return new WorkRequest("u1", tier, cost, "op");
	}

	@Test
	void score_premium_higher_than_free_at_same_load() {
		double premium = scorer.score(req(UserTier.PREMIUM, 1), 0);
		double free = scorer.score(req(UserTier.FREE, 1), 0);
		assertTrue(premium > free, "premium=" + premium + " free=" + free);
	}

	@Test
	void score_decreases_with_load() {
		double low = scorer.score(req(UserTier.STANDARD, 1), 0);
		double high = scorer.score(req(UserTier.STANDARD, 1), 8);
		assertTrue(high < low);
	}

	@Test
	void below_budget_always_admits() {
		Decision d = engine.triage(req(UserTier.FREE, 100));
		assertTrue(d.admit());
		assertFalse(d.degraded());
		engine.release();
	}

	@Test
	void overload_sheds_low_tier_high_cost_first() {
		// 8개 budget을 가득 채움
		for (int i = 0; i < 8; i++) {
			Decision a = engine.triage(req(UserTier.STANDARD, 1));
			assertTrue(a.admit());
		}
		Decision freeHeavy = engine.triage(req(UserTier.FREE, 200));
		assertFalse(freeHeavy.admit(), "FREE 고비용은 shed 되어야 함");

		Decision premium = engine.triage(req(UserTier.PREMIUM, 1));
		assertTrue(premium.admit(), "PREMIUM 저비용은 통과해야 함: score=" + premium.score());
	}

	@Test
	void overload_marks_borderline_as_degraded() {
		for (int i = 0; i < 8; i++) {
			engine.triage(req(UserTier.PREMIUM, 1));
		}
		// load=8이면 load_penalty=50, threshold admit=30, degrade=20
		// STANDARD: 60 - cost - 50 = 10 - cost — 비용을 올리면 degraded 영역으로
		Decision d = engine.triage(req(UserTier.STANDARD, 1));
		// score = 60 - 1 - 50 = 9 → shed
		assertFalse(d.admit() && !d.degraded());
		// PREMIUM cost=1 → 90 - 1 - 50 = 39 → admit
		Decision p = engine.triage(req(UserTier.PREMIUM, 1));
		assertTrue(p.admit() && !p.degraded());
		engine.release();
	}

	@Test
	void monitor_counters_track_decisions() {
		long before = monitor.totalShed();
		// 가득 채움
		for (int i = 0; i < 8; i++) engine.triage(req(UserTier.STANDARD, 1));
		// 추가 FREE 고비용은 shed
		engine.triage(req(UserTier.FREE, 500));
		assertTrue(monitor.totalShed() > before);
	}

	@Test
	void release_decrements_in_flight() {
		Decision d = engine.triage(req(UserTier.PREMIUM, 1));
		assertTrue(d.admit());
		int before = monitor.currentInFlight();
		engine.release();
		assertEquals(before - 1, monitor.currentInFlight());
	}
}
