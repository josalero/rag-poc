package com.example.rag.ingest;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ResumeIngestionServiceTest {

    @Mock
    EmbeddingStore<TextSegment> embeddingStore;

    @Mock
    EmbeddingStoreIngestor embeddingStoreIngestor;

    private ResumeIngestionService resumeIngestionService;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        resumeIngestionService = new ResumeIngestionService(embeddingStore, embeddingStoreIngestor);
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
}
