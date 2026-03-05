package com.example.rag.feature.metrics.service;

import com.example.rag.feature.metrics.model.*;

import org.springframework.stereotype.Service;

import java.util.concurrent.atomic.AtomicLong;

@Service
public class ObservabilityService {

    private final AtomicLong queryCount = new AtomicLong();
    private final AtomicLong queryErrors = new AtomicLong();
    private final AtomicLong queryLatencyTotalMs = new AtomicLong();
    private final AtomicLong querySourceTotal = new AtomicLong();
    private final AtomicLong ingestRunCount = new AtomicLong();
    private final AtomicLong ingestProcessed = new AtomicLong();
    private final AtomicLong ingestSkipped = new AtomicLong();
    private final AtomicLong candidateExtractionLlmAttempts = new AtomicLong();
    private final AtomicLong candidateExtractionLlmFailures = new AtomicLong();
    private final AtomicLong candidateExtractionValidationWarnings = new AtomicLong();

    public void recordQuery(long latencyMs, int sourcesReturned) {
        queryCount.incrementAndGet();
        queryLatencyTotalMs.addAndGet(Math.max(latencyMs, 0L));
        querySourceTotal.addAndGet(Math.max(sourcesReturned, 0));
    }

    public void recordQueryError() {
        queryErrors.incrementAndGet();
    }

    public void recordIngestRun(int processed, int skipped) {
        ingestRunCount.incrementAndGet();
        ingestProcessed.addAndGet(Math.max(processed, 0));
        ingestSkipped.addAndGet(Math.max(skipped, 0));
    }

    public void recordCandidateExtraction(boolean llmAttempted, boolean llmFailed, int validationWarnings) {
        if (llmAttempted) {
            candidateExtractionLlmAttempts.incrementAndGet();
        }
        if (llmFailed) {
            candidateExtractionLlmFailures.incrementAndGet();
        }
        candidateExtractionValidationWarnings.addAndGet(Math.max(validationWarnings, 0));
    }

    public ObservabilitySummary summary() {
        long totalQueries = queryCount.get();
        long errors = queryErrors.get();
        long totalLatency = queryLatencyTotalMs.get();
        long totalSources = querySourceTotal.get();
        return new ObservabilitySummary(
                totalQueries,
                errors,
                totalQueries == 0 ? 0.0d : (errors * 100.0d) / totalQueries,
                totalQueries == 0 ? 0.0d : (double) totalLatency / totalQueries,
                totalQueries == 0 ? 0.0d : (double) totalSources / totalQueries,
                ingestRunCount.get(),
                ingestProcessed.get(),
                ingestSkipped.get(),
                candidateExtractionLlmAttempts.get(),
                candidateExtractionLlmFailures.get(),
                candidateExtractionValidationWarnings.get()
        );
    }
}
