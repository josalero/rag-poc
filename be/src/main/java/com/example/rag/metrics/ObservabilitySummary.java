package com.example.rag.metrics;

public record ObservabilitySummary(
        long queryCount,
        long queryErrors,
        double queryErrorRate,
        double avgQueryLatencyMs,
        double avgSourcesPerQuery,
        long ingestRunCount,
        long ingestProcessed,
        long ingestSkipped,
        long candidateExtractionLlmAttempts,
        long candidateExtractionLlmFailures,
        long candidateExtractionValidationWarnings
) {
}
