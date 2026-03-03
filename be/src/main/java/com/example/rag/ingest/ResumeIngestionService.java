package com.example.rag.ingest;

import com.example.rag.audit.IngestAuditService;
import com.example.rag.candidate.CandidateProfileService;
import com.example.rag.metrics.ObservabilityService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import dev.langchain4j.store.embedding.filter.MetadataFilterBuilder;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Stream;

/**
 * Ingests resumes from a local folder (e.g. downloaded-resumes) into the vector store.
 * PDF text extraction uses Apache PDFBox Loader.loadPDF(bytes)
 * and PDFTextStripper.getText(document). Skips files that fail to parse. Sanitizes extracted text
 * (removes null bytes) so PostgreSQL UTF-8 text columns accept it.
 */
@Service
public class ResumeIngestionService {

    private static final Logger log = LoggerFactory.getLogger(ResumeIngestionService.class);

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingStoreIngestor embeddingStoreIngestor;
    private final CandidateProfileService candidateProfileService;
    private final IngestAuditService ingestAuditService;
    private final ObservabilityService observabilityService;
    private final ExecutorService ingestExecutor = Executors.newFixedThreadPool(2);

    @Value("${app.resumes.path:./downloaded-resumes}")
    private String resumesPath;
    @Value("${app.ingest.file-timeout-seconds:60}")
    private int fileIngestTimeoutSeconds;

    public ResumeIngestionService(EmbeddingStore<TextSegment> embeddingStore,
                                  EmbeddingStoreIngestor embeddingStoreIngestor,
                                  CandidateProfileService candidateProfileService,
                                  IngestAuditService ingestAuditService,
                                  ObservabilityService observabilityService) {
        this.embeddingStore = embeddingStore;
        this.embeddingStoreIngestor = embeddingStoreIngestor;
        this.candidateProfileService = candidateProfileService;
        this.ingestAuditService = ingestAuditService;
        this.observabilityService = observabilityService;
    }

    @PreDestroy
    public void shutdownExecutor() {
        ingestExecutor.shutdownNow();
    }

    public record UploadResume(String filename, byte[] content) {}

    public int ingestFromFolder() throws IOException {
        return ingestFromFolder(ev -> {});
    }

    /**
     * Ingests resumes from the configured folder and reports progress for each file
     * via the given consumer (e.g. for SSE streaming).
     */
    public int ingestFromFolder(Consumer<IngestProgressEvent> progress) throws IOException {
        Path baseDir = Path.of(resumesPath).toAbsolutePath();
        IngestAuditService.RunHandle auditRun = ingestAuditService.startRun();
        if (!Files.isDirectory(baseDir)) {
            log.warn("Resumes path is not a directory or does not exist: {}", baseDir);
            ingestAuditService.saveRun(auditRun, 0, 0);
            return 0;
        }

        List<Path> pdfs = new ArrayList<>();
        List<Path> nonPdfFiles = new ArrayList<>();
        try (Stream<Path> stream = Files.list(baseDir)) {
            stream.filter(Files::isRegularFile).forEach(path -> {
                String filename = path.getFileName().toString().toLowerCase();
                if (filename.endsWith(".pdf")) {
                    pdfs.add(path);
                } else {
                    nonPdfFiles.add(path);
                }
            });
        }

        int skipped = 0;
        for (Path nonPdf : nonPdfFiles) {
            String filename = nonPdf.getFileName().toString();
            String reason = "Only .pdf files are accepted for ingestion";
            skipped++;
            log.info("Skipped {} ({})", filename, reason);
            auditRun.addFileEvent(filename, "skipped", reason);
            progress.accept(IngestProgressEvent.fileSkipped(filename, reason));
        }

        int processed = 0;
        for (Path pdfPath : pdfs) {
            String filename = pdfPath.getFileName().toString();
            boolean ingested = false;
            boolean readFailed = false;
            try {
                byte[] bytes = Files.readAllBytes(pdfPath);
                ingested = processPdf(filename, bytes, pdfPath, auditRun, progress);
            } catch (IOException e) {
                readFailed = true;
                skipped++;
                log.warn("Skipped {}: {}", filename, e.getMessage());
                auditRun.addFileEvent(filename, "skipped", e.getMessage());
                progress.accept(IngestProgressEvent.fileSkipped(filename, e.getMessage()));
            }
            if (ingested) {
                processed++;
            } else if (!readFailed) {
                skipped++;
            }
        }

        log.info("Ingestion from folder completed. Processed: {}, skipped: {}", processed, skipped);
        ingestAuditService.saveRun(auditRun, processed, skipped);
        observabilityService.recordIngestRun(processed, skipped);
        progress.accept(IngestProgressEvent.done(processed));
        return processed;
    }

    public int ingestUploadedResumes(List<UploadResume> uploads, Consumer<IngestProgressEvent> progress) throws IOException {
        IngestAuditService.RunHandle auditRun = ingestAuditService.startRun();
        if (uploads == null || uploads.isEmpty()) {
            ingestAuditService.saveRun(auditRun, 0, 0);
            progress.accept(IngestProgressEvent.done(0));
            return 0;
        }

        int processed = 0;
        int skipped = 0;
        Map<String, Integer> filenameCount = new HashMap<>();
        for (int i = 0; i < uploads.size(); i++) {
            UploadResume upload = uploads.get(i);
            String filename = normalizeUploadFilename(upload.filename(), i + 1, filenameCount);
            if (!filename.toLowerCase().endsWith(".pdf")) {
                String reason = "Only .pdf files are accepted for ingestion";
                skipped++;
                log.info("Skipped {} ({})", filename, reason);
                auditRun.addFileEvent(filename, "skipped", reason);
                progress.accept(IngestProgressEvent.fileSkipped(filename, reason));
                continue;
            }

            byte[] content = upload.content();
            if (content == null || content.length == 0) {
                String reason = "Empty file";
                skipped++;
                log.info("Skipped {} ({})", filename, reason);
                auditRun.addFileEvent(filename, "skipped", reason);
                progress.accept(IngestProgressEvent.fileSkipped(filename, reason));
                continue;
            }

            Path tempFile = null;
            try {
                String suffix = filename.contains(".") ? filename.substring(filename.lastIndexOf('.')) : ".pdf";
                tempFile = Files.createTempFile("uploaded-resume-", suffix);
                Files.write(tempFile, content);

                boolean ingested = processPdf(filename, content, tempFile, auditRun, progress);
                if (ingested) {
                    processed++;
                } else {
                    skipped++;
                }
            } catch (Exception e) {
                skipped++;
                log.warn("Skipped {}: {}", filename, e.getMessage());
                auditRun.addFileEvent(filename, "skipped", e.getMessage());
                progress.accept(IngestProgressEvent.fileSkipped(filename, e.getMessage()));
            } finally {
                if (tempFile != null) {
                    try {
                        Files.deleteIfExists(tempFile);
                    } catch (IOException ignored) {
                        // Best-effort cleanup.
                    }
                }
            }
        }

        log.info("Uploaded ingestion completed. Processed: {}, skipped: {}", processed, skipped);
        ingestAuditService.saveRun(auditRun, processed, skipped);
        observabilityService.recordIngestRun(processed, skipped);
        progress.accept(IngestProgressEvent.done(processed));
        return processed;
    }

    private boolean processPdf(
            String filename,
            byte[] bytes,
            Path sourcePath,
            IngestAuditService.RunHandle auditRun,
            Consumer<IngestProgressEvent> progress) {
        try {
            String text = extractTextFromPdf(bytes);
            if (text == null) {
                log.warn("Skipped {} (PDF parse failed)", filename);
                auditRun.addFileEvent(filename, "skipped", "PDF parse failed");
                progress.accept(IngestProgressEvent.fileSkipped(filename, "PDF parse failed"));
                return false;
            }
            String sanitized = sanitizeText(text);
            String contentHash = contentHash(sanitized);
            java.util.Optional<String> duplicateOf = candidateProfileService.findDuplicateSource(filename, contentHash);
            if (duplicateOf.isPresent()) {
                String reason = "Duplicate resume content detected (same as " + duplicateOf.get() + "); merged under existing candidate";
                log.info("Skipped {} ({})", filename, reason);
                candidateProfileService.indexResume(filename, sourcePath, sanitized, contentHash);
                auditRun.addFileEvent(filename, "skipped", reason);
                progress.accept(IngestProgressEvent.fileSkipped(filename, reason));
                return false;
            }
            // Remove any existing segments for this source so re-ingestion does not duplicate
            embeddingStore.removeAll(MetadataFilterBuilder.metadataKey("source").isEqualTo(filename));
            Metadata meta = new Metadata();
            meta.put("source", filename);
            meta.put("file_name", filename);
            meta.put("content_hash", contentHash);
            Document doc = Document.from(sanitized, meta);
            ingestWithTimeout(doc);
            candidateProfileService.indexResume(filename, sourcePath, sanitized, contentHash);
            log.info("Ingested: {}", filename);
            auditRun.addFileEvent(filename, "ingested", null);
            progress.accept(IngestProgressEvent.fileIngested(filename));
            return true;
        } catch (Exception e) {
            log.warn("Skipped {}: {}", filename, e.getMessage());
            auditRun.addFileEvent(filename, "skipped", e.getMessage());
            progress.accept(IngestProgressEvent.fileSkipped(filename, e.getMessage()));
            return false;
        }
    }

    private void ingestWithTimeout(Document doc) throws Exception {
        int timeoutSeconds = Math.max(1, fileIngestTimeoutSeconds);
        CompletableFuture<Void> future = CompletableFuture.runAsync(() -> embeddingStoreIngestor.ingest(doc), ingestExecutor);
        try {
            future.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            future.cancel(true);
            throw new RuntimeException("Embedding request timed out after " + timeoutSeconds + "s", timeout);
        } catch (ExecutionException execution) {
            Throwable cause = execution.getCause() != null ? execution.getCause() : execution;
            if (cause instanceof Exception ex) {
                throw ex;
            }
            throw new RuntimeException(cause);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Ingestion interrupted", interrupted);
        }
    }

    private static String normalizeUploadFilename(String original, int index, Map<String, Integer> filenameCount) {
        String candidate = original != null ? original.trim() : "";
        if (candidate.isBlank()) {
            candidate = "uploaded-resume-" + index + ".pdf";
        }
        try {
            candidate = Path.of(candidate).getFileName().toString();
        } catch (Exception ignored) {
            // Keep original candidate when path parsing fails.
        }
        candidate = candidate.replace("\\", "_").replace("/", "_").trim();
        if (candidate.isBlank()) {
            candidate = "uploaded-resume-" + index + ".pdf";
        }

        int seen = filenameCount.getOrDefault(candidate.toLowerCase(), 0);
        filenameCount.put(candidate.toLowerCase(), seen + 1);
        if (seen == 0) {
            return candidate;
        }

        int extensionIndex = candidate.lastIndexOf('.');
        if (extensionIndex > 0) {
            String base = candidate.substring(0, extensionIndex);
            String ext = candidate.substring(extensionIndex);
            return base + "-" + (seen + 1) + ext;
        }
        return candidate + "-" + (seen + 1);
    }

    /**
     * Extracts text from PDF bytes using Apache PDFBox:
     * Apache PDFBox Loader.loadPDF and PDFTextStripper.
     */
    private static String extractTextFromPdf(byte[] bytes) {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            return stripper.getText(document);
        } catch (IOException e) {
            log.debug("Error parsing PDF: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Removes null bytes so text is safe for storage and display.
     */
    private static String sanitizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\u0000", "");
    }

    private static String contentHash(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.replaceAll("\\s+", " ").trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }
}
