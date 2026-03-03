package com.example.rag.ingest;

import com.example.rag.audit.IngestAuditService;
import com.example.rag.candidate.CandidateProfileService;
import com.example.rag.metrics.ObservabilityService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@SpringBootTest(classes = {
        ResumeIngestionService.class,
        IngestJobService.class,
        IngestJobIntegrationTest.TestBeans.class
})
class IngestJobIntegrationTest {

    @Autowired
    private ResumeIngestionService resumeIngestionService;
    @Autowired
    private IngestJobService ingestJobService;
    @Autowired
    private EmbeddingStoreIngestor embeddingStoreIngestor;
    @Autowired
    private CandidateProfileService candidateProfileService;
    @Autowired
    private IngestAuditService ingestAuditService;

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws Exception {
        ReflectionTestUtils.setField(resumeIngestionService, "resumesPath", tempDir.toString());
        ReflectionTestUtils.setField(resumeIngestionService, "fileIngestTimeoutSeconds", 1);
        when(candidateProfileService.findDuplicateSource(anyString(), anyString())).thenReturn(Optional.empty());
        when(ingestAuditService.startRun()).thenReturn(mock(IngestAuditService.RunHandle.class));

        Path resumeFile = tempDir.resolve("timeout.pdf");
        Files.write(resumeFile, createMinimalPdfBytes());

        doAnswer(invocation -> {
            Thread.sleep(3000);
            return null;
        }).when(embeddingStoreIngestor).ingest(any(Document.class));
    }

    @Test
    void startFolderJob_whenEmbeddingTimesOut_completesWithSkippedFile() throws InterruptedException {
        IngestJobStatus started = ingestJobService.startFolderJob();

        IngestJobStatus current = started;
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline) && !"completed".equals(current.status()) && !"failed".equals(current.status())) {
            Thread.sleep(100);
            current = ingestJobService.getJob(started.id());
        }

        assertThat(current).isNotNull();
        assertThat(current.status()).isEqualTo("completed");
        assertThat(current.processed()).isZero();
        assertThat(current.skipped()).isEqualTo(1);
        assertThat(current.message()).contains("completed");
    }

    @TestConfiguration
    static class TestBeans {
        @Bean
        @SuppressWarnings("unchecked")
        EmbeddingStore<TextSegment> embeddingStore() {
            return mock(EmbeddingStore.class);
        }

        @Bean
        EmbeddingStoreIngestor embeddingStoreIngestor() {
            return mock(EmbeddingStoreIngestor.class);
        }

        @Bean
        CandidateProfileService candidateProfileService() {
            return mock(CandidateProfileService.class);
        }

        @Bean
        IngestAuditService ingestAuditService() {
            return mock(IngestAuditService.class);
        }

        @Bean
        ObservabilityService observabilityService() {
            return mock(ObservabilityService.class);
        }
    }

    private static byte[] createMinimalPdfBytes() throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(50, 700);
                contentStream.showText("Integration timeout test content");
                contentStream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
