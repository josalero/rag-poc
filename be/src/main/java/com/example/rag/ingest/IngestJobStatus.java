package com.example.rag.ingest;

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
