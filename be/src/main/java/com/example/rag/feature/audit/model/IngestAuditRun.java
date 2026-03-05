package com.example.rag.feature.audit.model;

import java.time.Instant;
import java.util.List;

public record IngestAuditRun(
        String id,
        Instant startedAt,
        Instant finishedAt,
        int processed,
        int skipped,
        List<IngestAuditFileEntry> files
) {
}
