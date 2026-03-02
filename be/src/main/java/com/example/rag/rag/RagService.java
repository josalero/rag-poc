package com.example.rag.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagService {

    private static final int MAX_RESULTS = 5;
    private static final String PROMPT_TEMPLATE = """
        Answer the question based only on the following resume excerpts.
        List people or skills mentioned when relevant. If the context does not contain \
        relevant information, say so clearly.
        Context:
        %s
        Question: %s
        Answer:""";

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final ChatModel chatModel;

    public RagService(EmbeddingStore<TextSegment> embeddingStore,
                      EmbeddingModel embeddingModel,
                      ChatModel chatModel) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
    }

    public QueryResponse query(String question) {
        Embedding queryEmbedding = embeddingModel.embed(question).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(MAX_RESULTS)
                .build();
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(request).matches();

        String context = matches.stream()
                .map(m -> m.embedded().text())
                .collect(java.util.stream.Collectors.joining("\n\n"));
        List<QueryResponse.SourceSegment> sources = matches.stream()
                .map(m -> {
                    String src = m.embedded().metadata().getString("source");
                    return new QueryResponse.SourceSegment(
                            m.embedded().text(),
                            src != null ? src : "");
                })
                .toList();

        String prompt = String.format(PROMPT_TEMPLATE, context, question);
        String answer = chatModel.chat(prompt);
        return QueryResponse.of(answer, sources);
    }
}
