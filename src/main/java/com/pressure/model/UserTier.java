package com.pressure.model;

public enum UserTier {
    PREMIUM(3.0), STANDARD(2.0), FREE(1.0);

    private final double weight;

    UserTier(double weight) { this.weight = weight; }
    public double weight() { return weight; }
}
