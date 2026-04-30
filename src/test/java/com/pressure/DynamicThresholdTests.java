package com.pressure;

import com.pressure.triage.DynamicThresholdProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DynamicThresholdTests {

    private DynamicThresholdProvider newProvider() {
        return new DynamicThresholdProvider(new SimpleMeterRegistry(),
                30.0, 20.0, 60.0, 20.0, 10.0, 40.0, 0.5, 60.0);
    }

    @Test
    void startsAtBaseValues() {
        DynamicThresholdProvider t = newProvider();
        assertEquals(30.0, t.admitThreshold(), 0.5);
        assertEquals(20.0, t.degradeThreshold(), 0.5);
    }

    @Test
    void rejectsInvalidBounds() {
        var meters = new SimpleMeterRegistry();
        assertThrows(IllegalArgumentException.class, () ->
                new DynamicThresholdProvider(meters, 30, 40, 60, 20, 10, 40, 0.1, 1.0));
        assertThrows(IllegalArgumentException.class, () ->
                new DynamicThresholdProvider(meters, 30, 20, 60, 35, 10, 40, 0.1, 1.0));
    }

    @Test
    void persistentShedRaisesAdmitThreshold() {
        DynamicThresholdProvider t = newProvider();
        for (int i = 0; i < 200; i++) t.recordOutcome(true);
        assertTrue(t.admitThreshold() > 30.0, "shed pressure should raise admit threshold");
    }

    @Test
    void persistentAdmitLowersAdmitThreshold() {
        DynamicThresholdProvider t = newProvider();
        // first push it up by faking shed
        for (int i = 0; i < 100; i++) t.recordOutcome(true);
        double pushed = t.admitThreshold();
        // then drown it with allows
        for (int i = 0; i < 1000; i++) t.recordOutcome(false);
        assertTrue(t.admitThreshold() < pushed);
    }

    @Test
    void degradeAlwaysBelowAdmit() {
        DynamicThresholdProvider t = newProvider();
        for (int i = 0; i < 1000; i++) t.recordOutcome(true);
        assertTrue(t.degradeThreshold() < t.admitThreshold());
    }

    @Test
    void overrideTakesPrecedence() {
        DynamicThresholdProvider t = newProvider();
        t.overrideAdmit(45.0);
        for (int i = 0; i < 50; i++) t.recordOutcome(true);
        assertEquals(45.0, t.admitThreshold(), 0.0001);
        t.clearOverrides();
        assertNotEquals(45.0, t.admitThreshold());
    }

    @Test
    void resetReturnsToBase() {
        DynamicThresholdProvider t = newProvider();
        for (int i = 0; i < 200; i++) t.recordOutcome(true);
        t.resetState();
        assertEquals(30.0, t.admitThreshold(), 0.5);
        assertEquals(20.0, t.degradeThreshold(), 0.5);
    }

    @Test
    void boundsHonouredUnderExtremeInput() {
        DynamicThresholdProvider t = newProvider();
        for (int i = 0; i < 5000; i++) t.recordOutcome(true);
        assertTrue(t.admitThreshold() <= 60.0 + 1e-9);
        for (int i = 0; i < 5000; i++) t.recordOutcome(false);
        assertTrue(t.admitThreshold() >= 20.0 - 1e-9);
    }
}
