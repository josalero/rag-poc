package com.example.rag.rag;

import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class RagService {

    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final int DEFAULT_MAX_ALLOWED_RESULTS = 200;
    private static final double DEFAULT_MIN_SCORE = 0.0d;
    private static final String DEFAULT_NO_RESULTS_ANSWER =
            "I couldn't find relevant information in the ingested resumes.";
    private static final String PROMPT_TEMPLATE = """
        Answer the question based only on the following resume excerpts.
        List people or skills mentioned when relevant. If the context does not contain \
        relevant information, say so clearly.
        Return the response using EXACTLY this format:
        ANSWER:
        <2-4 sentences>

        KEY_FINDINGS:
        - <finding 1>
        - <finding 2>

        LIMITATIONS:
        - <what is uncertain or missing from the provided context>

        NEXT_STEPS:
        - <a useful follow-up query or clarification request>
        Context:
        %s
        Question: %s
        Response:""";

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final ChatModel chatModel;
    private final int defaultMaxResults;
    private final int maxAllowedResults;
    private final double minScore;
    private final String noResultsAnswer;

    RagService(EmbeddingStore<TextSegment> embeddingStore,
               EmbeddingModel embeddingModel,
               ChatModel chatModel) {
        this(
                embeddingStore,
                embeddingModel,
                chatModel,
                DEFAULT_MAX_RESULTS,
                DEFAULT_MAX_ALLOWED_RESULTS,
                DEFAULT_MIN_SCORE,
                DEFAULT_NO_RESULTS_ANSWER);
    }

    RagService(EmbeddingStore<TextSegment> embeddingStore,
               EmbeddingModel embeddingModel,
               ChatModel chatModel,
               int maxResults,
               double minScore,
               String noResultsAnswer) {
        this(
                embeddingStore,
                embeddingModel,
                chatModel,
                maxResults,
                DEFAULT_MAX_ALLOWED_RESULTS,
                minScore,
                noResultsAnswer);
    }

    @Autowired
    public RagService(EmbeddingStore<TextSegment> embeddingStore,
                      EmbeddingModel embeddingModel,
                      ChatModel chatModel,
                      @Value("${app.rag.max-results:50}") int maxResults,
                      @Value("${app.rag.max-allowed-results:200}") int maxAllowedResults,
                      @Value("${app.rag.min-score:0.0}") double minScore,
                      @Value("${app.rag.no-results-answer:I couldn't find relevant information in the ingested resumes.}") String noResultsAnswer) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
        int configuredDefault = maxResults > 0 ? maxResults : DEFAULT_MAX_RESULTS;
        int configuredMaxAllowed = maxAllowedResults > 0 ? maxAllowedResults : DEFAULT_MAX_ALLOWED_RESULTS;
        this.maxAllowedResults = Math.max(configuredDefault, configuredMaxAllowed);
        this.defaultMaxResults = Math.min(configuredDefault, this.maxAllowedResults);
        this.minScore = Math.max(0.0d, minScore);
        this.noResultsAnswer = noResultsAnswer != null && !noResultsAnswer.isBlank()
                ? noResultsAnswer.trim()
                : DEFAULT_NO_RESULTS_ANSWER;
    }

    public QueryResponse query(String question) {
        return query(question, null, null);
    }

    public QueryResponse query(String question, Integer requestedMaxResults, Double requestedMinScore) {
        int effectiveMaxResults = requestedMaxResults != null
                ? Math.min(Math.max(requestedMaxResults, 1), maxAllowedResults)
                : defaultMaxResults;
        double effectiveMinScore = requestedMinScore != null
                ? Math.max(0.0d, requestedMinScore)
                : minScore;

        Embedding queryEmbedding = embeddingModel.embed(question).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(effectiveMaxResults)
                .build();
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(request).matches().stream()
                .filter(m -> m.score() >= effectiveMinScore)
                .toList();

        if (matches.isEmpty()) {
            return QueryResponse.of(noResultsAnswer, List.of());
        }

        String context = matches.stream()
                .map(m -> m.embedded().text())
                .collect(java.util.stream.Collectors.joining("\n\n"));
        List<QueryResponse.SourceSegment> sources = matches.stream()
                .map(m -> {
                    String src = m.embedded().metadata() != null
                            ? m.embedded().metadata().getString("source")
                            : null;
                    return new QueryResponse.SourceSegment(
                            m.embedded().text(),
                            src != null ? src : "",
                            m.score());
                })
                .toList();

        String prompt = String.format(PROMPT_TEMPLATE, context, question);
        String answer = chatModel.chat(prompt);
        return QueryResponse.of(answer, sources);
    }
}
