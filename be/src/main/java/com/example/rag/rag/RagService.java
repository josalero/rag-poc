package com.example.rag.rag;

import com.example.rag.candidate.CandidateProfileService;
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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class RagService {

    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final int DEFAULT_MAX_ALLOWED_RESULTS = 200;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int DEFAULT_MAX_PAGE_SIZE = 100;
    private static final int MAX_CONTEXT_SEGMENTS = 20;
    private static final double DEFAULT_MIN_SCORE = 0.75d;
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
    private final CandidateProfileService candidateProfileService;
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
                null,
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
                null,
                maxResults,
                DEFAULT_MAX_ALLOWED_RESULTS,
                minScore,
                noResultsAnswer);
    }

    RagService(EmbeddingStore<TextSegment> embeddingStore,
               EmbeddingModel embeddingModel,
               ChatModel chatModel,
               int maxResults,
               int maxAllowedResults,
               double minScore,
               String noResultsAnswer) {
        this(
                embeddingStore,
                embeddingModel,
                chatModel,
                null,
                maxResults,
                maxAllowedResults,
                minScore,
                noResultsAnswer);
    }

    @Autowired
    public RagService(EmbeddingStore<TextSegment> embeddingStore,
                      EmbeddingModel embeddingModel,
                      ChatModel chatModel,
                      CandidateProfileService candidateProfileService,
                      @Value("${app.rag.max-results:50}") int maxResults,
                      @Value("${app.rag.max-allowed-results:200}") int maxAllowedResults,
                      @Value("${app.rag.min-score:0.75}") double minScore,
                      @Value("${app.rag.no-results-answer:I couldn't find relevant information in the ingested resumes.}") String noResultsAnswer) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
        this.candidateProfileService = candidateProfileService;
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
        return query(question, null, null, null, null);
    }

    public QueryResponse query(String question, Integer requestedMaxResults, Double requestedMinScore) {
        return query(question, requestedMaxResults, requestedMinScore, null, null);
    }

    public QueryResponse query(
            String question,
            Integer requestedMaxResults,
            Double requestedMinScore,
            Integer requestedPage,
            Integer requestedPageSize) {
        int effectiveMaxResults = requestedMaxResults != null
                ? Math.min(Math.max(requestedMaxResults, 1), maxAllowedResults)
                : defaultMaxResults;
        double effectiveMinScore = requestedMinScore != null
                ? Math.max(0.0d, requestedMinScore)
                : minScore;
        int effectivePage = requestedPage != null ? Math.max(1, requestedPage) : 1;
        int effectivePageSize = requestedPageSize != null
                ? Math.min(Math.max(1, requestedPageSize), DEFAULT_MAX_PAGE_SIZE)
                : DEFAULT_PAGE_SIZE;

        Embedding queryEmbedding = embeddingModel.embed(question).content();
        EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                .queryEmbedding(queryEmbedding)
                .maxResults(effectiveMaxResults)
                .build();
        List<EmbeddingMatch<TextSegment>> matches = embeddingStore.search(request).matches().stream()
                .filter(m -> m.score() >= effectiveMinScore)
                .toList();
        List<EmbeddingMatch<TextSegment>> uniqueMatches = deduplicateMatches(matches);

        if (uniqueMatches.isEmpty()) {
            return QueryResponse.of(noResultsAnswer, List.of(), effectivePage, effectivePageSize, 0);
        }

        List<EmbeddingMatch<TextSegment>> contextMatches = uniqueMatches.stream()
                .limit(MAX_CONTEXT_SEGMENTS)
                .toList();
        String context = contextMatches.stream()
                .map(m -> m.embedded().text())
                .collect(java.util.stream.Collectors.joining("\n\n"));
        int totalSources = uniqueMatches.size();
        int startIndex = Math.min((effectivePage - 1) * effectivePageSize, totalSources);
        int endIndex = Math.min(startIndex + effectivePageSize, totalSources);

        List<QueryResponse.SourceSegment> sources = new java.util.ArrayList<>();
        for (int i = startIndex; i < endIndex; i++) {
            EmbeddingMatch<TextSegment> match = uniqueMatches.get(i);
            String source = extractSource(match);
            String candidateId = source.isBlank() || candidateProfileService == null
                    ? ""
                    : candidateProfileService.getBySourceFilename(source)
                    .map(profile -> profile.id())
                    .orElse("");
            sources.add(new QueryResponse.SourceSegment(
                    match.embedded().text(),
                    source,
                    match.score(),
                    i + 1,
                    candidateId));
        }

        String prompt = String.format(PROMPT_TEMPLATE, context, question);
        String answer = chatModel.chat(prompt);
        return QueryResponse.of(answer, sources, effectivePage, effectivePageSize, totalSources);
    }

    private static List<EmbeddingMatch<TextSegment>> deduplicateMatches(List<EmbeddingMatch<TextSegment>> matches) {
        if (matches == null || matches.isEmpty()) {
            return List.of();
        }
        Map<String, EmbeddingMatch<TextSegment>> bestByKey = new LinkedHashMap<>();
        int fallbackIndex = 0;
        for (EmbeddingMatch<TextSegment> match : matches) {
            String source = extractSource(match);
            String key;
            if (!source.isBlank()) {
                key = "source:" + source.toLowerCase(Locale.ROOT);
            } else {
                String text = match.embedded() != null && match.embedded().text() != null
                        ? match.embedded().text().replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT)
                        : "";
                if (text.isBlank()) {
                    key = "fallback:" + (fallbackIndex++);
                } else {
                    key = "text:" + text;
                }
            }
            bestByKey.putIfAbsent(key, match);
        }
        return List.copyOf(bestByKey.values());
    }

    private static String extractSource(EmbeddingMatch<TextSegment> match) {
        if (match == null || match.embedded() == null || match.embedded().metadata() == null) {
            return "";
        }
        String source = match.embedded().metadata().getString("source");
        return source != null ? source.trim() : "";
    }
}
