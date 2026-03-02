package com.example.rag.ingest;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api")
public class IngestController {

    private static final long STREAM_TIMEOUT_MS = 300_000L;

    private final ResumeIngestionService resumeIngestionService;

    public IngestController(ResumeIngestionService resumeIngestionService) {
        this.resumeIngestionService = resumeIngestionService;
    }

    @PostMapping("/ingest")
    public ResponseEntity<Map<String, Object>> ingest() {
        try {
            int count = resumeIngestionService.ingestFromFolder();
            return ResponseEntity.ok(Map.of("documentsProcessed", count));
        } catch (IOException e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("error", "Ingestion failed", "message", e.getMessage()));
        }
    }

    /**
     * Streams ingest progress as Server-Sent Events. Each file emits an event
     * (ingested or skipped); a final "done" event includes documentsProcessed.
     * Runs ingestion in a background thread so the client receives events as they happen.
     */
    @PostMapping(value = "/ingest/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter ingestStream() {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        CompletableFuture.runAsync(() -> {
            try {
                resumeIngestionService.ingestFromFolder(ev -> {
                    try {
                        emitter.send(ev);
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to send ingest event", e);
                    }
                });
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }
}
