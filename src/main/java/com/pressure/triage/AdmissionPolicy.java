package com.pressure.triage;

import com.pressure.model.WorkRequest;

/**
 * Pluggable admission policy. Computes a score for an incoming request given current load.
 * Higher score = higher chance of admission.
 *
 * Replace the default bean with a custom AdmissionPolicy bean to plug in:
 *  - PID/EWMA-based adaptive shedding
 *  - tenant-aware quota policies
 *  - learned/ML-based scoring
 *
 * Invariants:
 *  - score(req, inFlight) is deterministic given inputs
 *  - implementations must be thread-safe (called concurrently from TriageEngine)
 *  - returned value may be negative (over-loaded request)
 */
public interface AdmissionPolicy {

    String name();

    double score(WorkRequest request, int currentInFlight);
}
