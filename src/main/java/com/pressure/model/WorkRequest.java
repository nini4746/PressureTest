package com.pressure.model;

public record WorkRequest(String userId, UserTier tier, int costUnits, String operation) {}
