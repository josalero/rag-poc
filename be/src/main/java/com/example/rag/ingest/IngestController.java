package com.example.rag.ingest;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api")
public class IngestController {

    private static final long STREAM_TIMEOUT_MS = 300_000L;

    private final ResumeIngestionService resumeIngestionService;
    private final IngestJobService ingestJobService;

    public IngestController(ResumeIngestionService resumeIngestionService, IngestJobService ingestJobService) {
        this.resumeIngestionService = resumeIngestionService;
        this.ingestJobService = ingestJobService;
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

    @PostMapping(value = "/ingest/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> ingestUploaded(
            @RequestParam(name = "files", required = false) List<MultipartFile> files) {
        try {
            List<ResumeIngestionService.UploadResume> uploads = new ArrayList<>();
            if (files != null) {
                for (MultipartFile file : files) {
                    uploads.add(new ResumeIngestionService.UploadResume(file.getOriginalFilename(), file.getBytes()));
                }
            }

            List<IngestProgressEvent> events = new ArrayList<>();
            int count = resumeIngestionService.ingestUploadedResumes(uploads, events::add);
            List<IngestProgressEvent> fileEvents = events.stream()
                    .filter(event -> "file".equals(event.type()))
                    .toList();
            return ResponseEntity.ok(Map.of(
                    "documentsProcessed", count,
                    "fileEvents", fileEvents
            ));
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

    @PostMapping("/ingest/jobs/folder")
    public ResponseEntity<IngestJobStatus> startFolderIngestJob() {
        return ResponseEntity.ok(ingestJobService.startFolderJob());
    }

    @PostMapping("/ingest/jobs")
    public ResponseEntity<IngestJobStatus> startDefaultJob() {
        return ResponseEntity.ok(ingestJobService.startFolderJob());
    }

    @org.springframework.web.bind.annotation.GetMapping("/ingest/jobs")
    public ResponseEntity<List<IngestJobStatus>> listJobs(
            @RequestParam(defaultValue = "30") int limit) {
        return ResponseEntity.ok(ingestJobService.listJobs(limit));
    }

    @org.springframework.web.bind.annotation.GetMapping("/ingest/jobs/{id}")
    public ResponseEntity<IngestJobStatus> getJob(@org.springframework.web.bind.annotation.PathVariable String id) {
        IngestJobStatus status = ingestJobService.getJob(id);
        if (status == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(status);
    }
}
