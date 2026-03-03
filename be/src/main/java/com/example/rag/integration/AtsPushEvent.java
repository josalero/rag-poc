package com.example.rag.integration;

import java.time.Instant;

public record AtsPushEvent(
        String candidateId,
        String jobId,
        String notes,
        String status,
        Instant createdAt
) {
}
