package com.example.rag.ingest;

import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Service
public class IngestJobService {

    private final ResumeIngestionService resumeIngestionService;
    private final Map<String, MutableJob> jobs = new ConcurrentHashMap<>();
    private final ExecutorService jobExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public IngestJobService(ResumeIngestionService resumeIngestionService) {
        this.resumeIngestionService = resumeIngestionService;
    }

    public IngestJobStatus startFolderJob() {
        String id = UUID.randomUUID().toString();
        MutableJob job = new MutableJob(id, "folder");
        jobs.put(id, job);

        CompletableFuture.runAsync(() -> runFolderJob(job), jobExecutor);
        return job.toSnapshot();
    }

    @PreDestroy
    public void shutdownExecutor() {
        jobExecutor.shutdownNow();
    }

    public List<IngestJobStatus> listJobs(int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 200));
        return jobs.values().stream()
                .sorted((a, b) -> b.startedAt.compareTo(a.startedAt))
                .limit(safeLimit)
                .map(MutableJob::toSnapshot)
                .toList();
    }

    public IngestJobStatus getJob(String id) {
        MutableJob job = jobs.get(id);
        return job != null ? job.toSnapshot() : null;
    }

    private void runFolderJob(MutableJob job) {
        job.markRunning();
        try {
            resumeIngestionService.ingestFromFolder(event -> {
                if (!"file".equals(event.type())) {
                    return;
                }
                String filename = event.filename() != null ? event.filename() : "unknown";
                if ("ingested".equals(event.status())) {
                    job.recordIngested(filename);
                } else if ("skipped".equals(event.status())) {
                    String reason = event.reason() != null && !event.reason().isBlank()
                            ? ": " + event.reason()
                            : "";
                    job.recordSkipped(filename, reason);
                }
            });
            job.markCompleted();
        } catch (IOException e) {
            job.markFailed(e.getMessage());
        } catch (RuntimeException e) {
            job.markFailed(e.getMessage());
            throw e;
        }
    }

    private static final class MutableJob {
        private final String id;
        private final String type;
        private final Instant startedAt;
        private String status;
        private int processed;
        private int skipped;
        private Instant finishedAt;
        private String message;

        private MutableJob(String id, String type) {
            this.id = id;
            this.type = type;
            this.startedAt = Instant.now();
            this.status = "queued";
            this.processed = 0;
            this.skipped = 0;
            this.finishedAt = null;
            this.message = "";
        }

        private synchronized void markRunning() {
            this.status = "running";
        }

        private synchronized void recordIngested(String filename) {
            this.processed += 1;
            this.message = "Ingested " + filename;
        }

        private synchronized void recordSkipped(String filename, String reasonSuffix) {
            this.skipped += 1;
            this.message = "Skipped " + filename + reasonSuffix;
        }

        private synchronized void markCompleted() {
            this.message = "Folder ingestion completed (" + this.processed + " processed, " + this.skipped + " skipped)";
            this.finishedAt = Instant.now();
            this.status = "completed";
        }

        private synchronized void markFailed(String failureMessage) {
            this.message = failureMessage != null ? failureMessage : "Ingestion failed";
            this.finishedAt = Instant.now();
            this.status = "failed";
        }

        private IngestJobStatus toSnapshot() {
            synchronized (this) {
                return new IngestJobStatus(
                        id,
                        type,
                        status,
                        processed,
                        skipped,
                        startedAt,
                        finishedAt,
                        message
                );
            }
        }
    }
}
