package com.example.rag.ingest;

import com.example.rag.audit.IngestAuditService;
import com.example.rag.candidate.CandidateProfileService;
import com.example.rag.metrics.ObservabilityService;
import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class ResumeIngestionServiceTest {

    @Mock
    EmbeddingStore<TextSegment> embeddingStore;

    @Mock
    EmbeddingStoreIngestor embeddingStoreIngestor;

    @Mock
    CandidateProfileService candidateProfileService;

    @Mock
    IngestAuditService ingestAuditService;
    @Mock
    ObservabilityService observabilityService;

    private ResumeIngestionService resumeIngestionService;
    private IngestAuditService.RunHandle runHandle;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        runHandle = org.mockito.Mockito.mock(IngestAuditService.RunHandle.class);
        org.mockito.Mockito.when(ingestAuditService.startRun()).thenReturn(runHandle);
        resumeIngestionService = new ResumeIngestionService(
                embeddingStore,
                embeddingStoreIngestor,
                candidateProfileService,
                ingestAuditService,
                observabilityService);
        ReflectionTestUtils.setField(resumeIngestionService, "resumesPath", tempDir.toString());
    }

    @Test
    void ingestFromFolder_whenDirectoryEmpty_returnsZero() throws IOException {
        int count = resumeIngestionService.ingestFromFolder();

        assertThat(count).isZero();
    }

    @Test
    void ingestFromFolder_whenPathNotDirectory_returnsZero() throws IOException {
        ReflectionTestUtils.setField(resumeIngestionService, "resumesPath", "/nonexistent-path-" + System.nanoTime());

        int count = resumeIngestionService.ingestFromFolder();

        assertThat(count).isZero();
    }

    @Test
    void ingestFromFolder_whenNonPdfFilePresent_reportsSkippedReason(@TempDir Path tempDir) throws IOException {
        java.nio.file.Files.writeString(tempDir.resolve("candidate.docx"), "not a pdf");
        ReflectionTestUtils.setField(resumeIngestionService, "resumesPath", tempDir.toString());
        List<IngestProgressEvent> events = new ArrayList<>();

        int count = resumeIngestionService.ingestFromFolder(events::add);

        assertThat(count).isZero();
        assertThat(events).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo("file");
            assertThat(event.filename()).isEqualTo("candidate.docx");
            assertThat(event.reason()).contains("Only .pdf files are accepted");
        });
        verify(runHandle).addFileEvent("candidate.docx", "skipped", "Only .pdf files are accepted for ingestion");
    }

    @Test
    void ingestUploadedResumes_whenNonPdfFilePresent_reportsSkippedReason() throws IOException {
        List<IngestProgressEvent> events = new ArrayList<>();

        int count = resumeIngestionService.ingestUploadedResumes(
                List.of(new ResumeIngestionService.UploadResume("candidate.docx", "not a pdf".getBytes())),
                events::add
        );

        assertThat(count).isZero();
        assertThat(events).anySatisfy(event -> {
            assertThat(event.type()).isEqualTo("file");
            assertThat(event.filename()).isEqualTo("candidate.docx");
            assertThat(event.reason()).contains("Only .pdf files are accepted");
        });
        verify(runHandle).addFileEvent("candidate.docx", "skipped", "Only .pdf files are accepted for ingestion");
    }

    @Test
    void ingestUploadedResumes_whenEmbeddingTimesOut_marksFileSkipped() throws IOException {
        List<IngestProgressEvent> events = new ArrayList<>();
        ReflectionTestUtils.setField(resumeIngestionService, "fileIngestTimeoutSeconds", 1);
        org.mockito.Mockito.when(candidateProfileService.findDuplicateSource(anyString(), anyString())).thenReturn(Optional.empty());
        doAnswer(invocation -> {
            Thread.sleep(3000);
            return null;
        }).when(embeddingStoreIngestor).ingest(any(Document.class));

        int count = resumeIngestionService.ingestUploadedResumes(
                List.of(new ResumeIngestionService.UploadResume("timeout.pdf", createMinimalPdfBytes())),
                events::add
        );

        assertThat(count).isZero();
        IngestProgressEvent fileEvent = events.stream()
                .filter(event -> "file".equals(event.type()))
                .findFirst()
                .orElseThrow();
        assertThat(fileEvent.filename()).isEqualTo("timeout.pdf");
        assertThat(fileEvent.status()).isEqualTo("skipped");
        assertThat(fileEvent.reason()).containsIgnoringCase("timed out");
    }

    private static byte[] createMinimalPdfBytes() throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream contentStream = new PDPageContentStream(document, page)) {
                contentStream.beginText();
                contentStream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                contentStream.newLineAtOffset(50, 700);
                contentStream.showText("Timeout integration test resume content");
                contentStream.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
