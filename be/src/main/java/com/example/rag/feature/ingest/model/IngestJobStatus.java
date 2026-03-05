package com.example.rag.feature.ingest.model;

import java.time.Instant;

public record IngestJobStatus(
        String id,
        String type,
        String status,
        int processed,
        int skipped,
        Instant startedAt,
        Instant finishedAt,
        String message
) {
}
