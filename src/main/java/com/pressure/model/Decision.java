package com.pressure.model;

public record Decision(boolean admit, double score, String reason, boolean degraded) {
    public static Decision admit(double score) {
        return new Decision(true, score, "ok", false);
    }
    public static Decision degraded(double score) {
        return new Decision(true, score, "degraded", true);
    }
    public static Decision shed(double score, String why) {
        return new Decision(false, score, why, false);
    }
}
