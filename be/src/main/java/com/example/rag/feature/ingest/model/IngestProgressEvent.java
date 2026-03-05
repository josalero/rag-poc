package com.example.rag.feature.ingest.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Event sent during streaming ingest to report progress per file.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record IngestProgressEvent(
        String type,
        String filename,
        String status,
        String reason,
        Integer documentsProcessed
) {
    public static IngestProgressEvent fileIngested(String filename) {
        return new IngestProgressEvent("file", filename, "ingested", null, null);
    }

    public static IngestProgressEvent fileSkipped(String filename, String reason) {
        return new IngestProgressEvent("file", filename, "skipped", reason, null);
    }

    public static IngestProgressEvent done(int documentsProcessed) {
        return new IngestProgressEvent("done", null, null, null, documentsProcessed);
    }
}
