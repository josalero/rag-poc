package com.example.rag.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RagServiceTest {

    @Mock EmbeddingStore<TextSegment> embeddingStore;
    @Mock EmbeddingModel embeddingModel;
    @Mock ChatModel chatModel;

    private RagService ragService;

    @BeforeEach
    void setUp() {
        ragService = new RagService(embeddingStore, embeddingModel, chatModel);
    }

    @Test
    void query_withValidQuestion_invokesModelWithContextAndReturnsAnswer() {
        Embedding queryEmbedding = new Embedding(new float[]{0.1f});
        TextSegment segment = TextSegment.from("John has Java and Spring experience.");
        segment.metadata().put("source", "resume1.pdf");
        EmbeddingMatch<TextSegment> match = new EmbeddingMatch<>(0.9, "id1", queryEmbedding, segment);
        EmbeddingSearchResult<TextSegment> searchResult = new EmbeddingSearchResult<>(List.of(match));

        when(embeddingModel.embed(any(String.class))).thenReturn(Response.from(queryEmbedding));
        when(embeddingStore.search(any())).thenReturn(searchResult);
        when(chatModel.chat(anyString())).thenReturn("John has Java experience.");

        QueryResponse response = ragService.query("Who has Java experience?");

        assertThat(response.answer()).isEqualTo("John has Java experience.");
        assertThat(response.sources()).hasSize(1);
        assertThat(response.sources().get(0).text()).contains("Java");
        verify(chatModel).chat(org.mockito.ArgumentMatchers.argThat(
                (String s) -> s != null && s.contains("John has Java") && s.contains("Who has Java experience?")));
    }
}
