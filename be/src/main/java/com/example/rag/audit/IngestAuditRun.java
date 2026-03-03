package com.example.rag.audit;

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
