package com.example.rag.ingest;

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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    @Value("${app.resumes.path:./downloaded-resumes}")
    private String resumesPath;

    public ResumeIngestionService(EmbeddingStore<TextSegment> embeddingStore,
                                  EmbeddingStoreIngestor embeddingStoreIngestor) {
        this.embeddingStore = embeddingStore;
        this.embeddingStoreIngestor = embeddingStoreIngestor;
    }

    public int ingestFromFolder() throws IOException {
        return ingestFromFolder(ev -> {});
    }

    /**
     * Ingests resumes from the configured folder and reports progress for each file
     * via the given consumer (e.g. for SSE streaming).
     */
    public int ingestFromFolder(Consumer<IngestProgressEvent> progress) throws IOException {
        Path baseDir = Path.of(resumesPath).toAbsolutePath();
        if (!Files.isDirectory(baseDir)) {
            log.warn("Resumes path is not a directory or does not exist: {}", baseDir);
            return 0;
        }

        List<Path> pdfs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(baseDir)) {
            stream.filter(p -> Files.isRegularFile(p) && p.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .forEach(pdfs::add);
        }

        int processed = 0;
        int skipped = 0;
        for (Path pdfPath : pdfs) {
            String filename = pdfPath.getFileName().toString();
            try {
                byte[] bytes = Files.readAllBytes(pdfPath);
                String text = extractTextFromPdf(bytes);
                if (text == null) {
                    log.warn("Skipped {} (PDF parse failed)", filename);
                    skipped++;
                    progress.accept(IngestProgressEvent.fileSkipped(filename, "PDF parse failed"));
                    continue;
                }
                String sanitized = sanitizeText(text);
                // Remove any existing segments for this source so re-ingestion does not duplicate
                embeddingStore.removeAll(MetadataFilterBuilder.metadataKey("source").isEqualTo(filename));
                Metadata meta = new Metadata();
                meta.put("source", filename);
                meta.put("file_name", filename);
                Document doc = Document.from(sanitized, meta);
                embeddingStoreIngestor.ingest(doc);
                processed++;
                log.info("Ingested: {}", filename);
                progress.accept(IngestProgressEvent.fileIngested(filename));
            } catch (Exception e) {
                skipped++;
                log.warn("Skipped {}: {}", filename, e.getMessage());
                progress.accept(IngestProgressEvent.fileSkipped(filename, e.getMessage()));
            }
        }

        log.info("Ingestion from folder completed. Processed: {}, skipped: {}", processed, skipped);
        progress.accept(IngestProgressEvent.done(processed));
        return processed;
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
}
