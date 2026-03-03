package com.example.rag.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
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
        assertThat(response.sources().get(0).score()).isCloseTo(0.92, org.assertj.core.data.Offset.offset(0.0001));
        assertThat(response.sources().get(0).vectorScore()).isEqualTo(0.9);
        assertThat(response.sources().get(0).keywordScore()).isGreaterThan(0.0);
        verify(chatModel).chat(org.mockito.ArgumentMatchers.argThat(
                (String s) -> s != null && s.contains("John has Java") && s.contains("Who has Java experience?")));
    }

    @Test
    void query_whenNoMatchPassesScoreThreshold_returnsFallbackAndSkipsLlmCall() {
        RagService strictRagService = new RagService(
                embeddingStore,
                embeddingModel,
                chatModel,
                5,
                0.95,
                "No relevant context found.");

        Embedding queryEmbedding = new Embedding(new float[]{0.2f});
        TextSegment weakSegment = TextSegment.from("General profile summary.");
        EmbeddingMatch<TextSegment> weakMatch = new EmbeddingMatch<>(0.41, "id2", queryEmbedding, weakSegment);
        EmbeddingSearchResult<TextSegment> searchResult = new EmbeddingSearchResult<>(List.of(weakMatch));

        when(embeddingModel.embed(any(String.class))).thenReturn(Response.from(queryEmbedding));
        when(embeddingStore.search(any())).thenReturn(searchResult);

        QueryResponse response = strictRagService.query("Who knows Rust?");

        assertThat(response.answer()).isEqualTo("No relevant context found.");
        assertThat(response.sources()).isEmpty();
        verify(chatModel, never()).chat(anyString());
    }

    @Test
    void query_withRequestOverrides_appliesPerQueryLimitAndThreshold() {
        RagService configurableRagService = new RagService(
                embeddingStore,
                embeddingModel,
                chatModel,
                5,
                200,
                0.0,
                "No relevant context found.");

        Embedding queryEmbedding = new Embedding(new float[]{0.3f});
        TextSegment strongSegment = TextSegment.from("Alice knows Java.");
        strongSegment.metadata().put("source", "resume-strong.pdf");
        TextSegment weakSegment = TextSegment.from("Mentions Kotlin.");
        weakSegment.metadata().put("source", "resume-weak.pdf");
        EmbeddingMatch<TextSegment> strongMatch = new EmbeddingMatch<>(0.88, "id3", queryEmbedding, strongSegment);
        EmbeddingMatch<TextSegment> weakMatch = new EmbeddingMatch<>(0.15, "id4", queryEmbedding, weakSegment);
        EmbeddingSearchResult<TextSegment> searchResult = new EmbeddingSearchResult<>(List.of(strongMatch, weakMatch));

        when(embeddingModel.embed(any(String.class))).thenReturn(Response.from(queryEmbedding));
        when(embeddingStore.search(any())).thenReturn(searchResult);
        when(chatModel.chat(anyString())).thenReturn("Alice has Java experience.");

        QueryResponse response = configurableRagService.query("Who knows Java?", 150, 0.5);

        assertThat(response.answer()).isEqualTo("Alice has Java experience.");
        assertThat(response.sources()).hasSize(1);
        assertThat(response.sources().get(0).source()).isEqualTo("resume-strong.pdf");

        ArgumentCaptor<EmbeddingSearchRequest> requestCaptor = ArgumentCaptor.forClass(EmbeddingSearchRequest.class);
        verify(embeddingStore).search(requestCaptor.capture());
        assertThat(requestCaptor.getValue().maxResults()).isEqualTo(150);
    }

    @Test
    void query_withDuplicateChunksFromSameSource_returnsUniqueSources() {
        Embedding queryEmbedding = new Embedding(new float[]{0.4f});

        TextSegment firstChunk = TextSegment.from("Alice has strong Java backend experience.");
        firstChunk.metadata().put("source", "alice.pdf");
        TextSegment secondChunk = TextSegment.from("Alice also worked with Spring Boot and microservices.");
        secondChunk.metadata().put("source", "alice.pdf");
        TextSegment otherSourceChunk = TextSegment.from("Bob has Kubernetes and Terraform experience.");
        otherSourceChunk.metadata().put("source", "bob.pdf");

        EmbeddingMatch<TextSegment> aliceBest = new EmbeddingMatch<>(0.95, "id-a1", queryEmbedding, firstChunk);
        EmbeddingMatch<TextSegment> aliceDuplicate = new EmbeddingMatch<>(0.91, "id-a2", queryEmbedding, secondChunk);
        EmbeddingMatch<TextSegment> bobMatch = new EmbeddingMatch<>(0.88, "id-b1", queryEmbedding, otherSourceChunk);
        EmbeddingSearchResult<TextSegment> searchResult = new EmbeddingSearchResult<>(List.of(aliceBest, aliceDuplicate, bobMatch));

        when(embeddingModel.embed(any(String.class))).thenReturn(Response.from(queryEmbedding));
        when(embeddingStore.search(any())).thenReturn(searchResult);
        when(chatModel.chat(anyString())).thenReturn("Alice and Bob match the query.");

        QueryResponse response = ragService.query("Who has backend and platform skills?", 50, 0.0, 1, 10, false);

        assertThat(response.sources()).hasSize(2);
        assertThat(response.totalSources()).isEqualTo(2);
        assertThat(response.sources().get(0).source()).isEqualTo("alice.pdf");
        assertThat(response.sources().get(0).score()).isGreaterThan(0.8);
        assertThat(response.sources().get(0).vectorScore()).isEqualTo(0.95);
        assertThat(response.sources().get(1).source()).isEqualTo("bob.pdf");
    }
}
