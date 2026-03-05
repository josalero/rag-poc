package com.example.rag.feature.audit.model;

import java.time.Instant;

public record IngestAuditFileEntry(
        String filename,
        String status,
        String reason,
        Instant timestamp
) {
}
