package com.example.rag.feature.integration.model;

import java.time.Instant;

public record AtsPushEvent(
        String candidateId,
        String jobId,
        String notes,
        String status,
        Instant createdAt
) {
}
