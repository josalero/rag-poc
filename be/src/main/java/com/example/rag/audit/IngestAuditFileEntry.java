package com.example.rag.audit;

import java.time.Instant;

public record IngestAuditFileEntry(
        String filename,
        String status,
        String reason,
        Instant timestamp
) {
}
