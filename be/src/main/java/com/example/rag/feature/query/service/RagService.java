package com.example.rag.feature.query.service;

import com.example.rag.feature.candidate.service.CandidateProfileService;
import com.example.rag.feature.query.model.QueryExplainability;
import com.example.rag.feature.query.model.QueryResponse;
import com.example.rag.feature.feedback.service.QueryFeedbackService;
import com.example.rag.feature.metrics.service.ObservabilityService;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.ContentMetadata;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.logical.And;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static dev.langchain4j.store.embedding.filter.MetadataFilterBuilder.metadataKey;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);
    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final int DEFAULT_MAX_ALLOWED_RESULTS = 200;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int DEFAULT_MAX_PAGE_SIZE = 100;
    private static final int DEFAULT_QUERY_CACHE_MAX_ENTRIES = 256;
    private static final long DEFAULT_QUERY_CACHE_TTL_MILLIS = 15_000L;
    private static final int MAX_CONTEXT_SEGMENTS = 20;
    private static final int MAX_EXPLAINABILITY_TERMS = 8;
    private static final double DEFAULT_MIN_SCORE = 0.75d;
    private static final double VECTOR_WEIGHT = 0.8d;
    private static final double KEYWORD_WEIGHT = 0.2d;
    private static final String DEFAULT_NO_RESULTS_ANSWER =
            "I couldn't find relevant information in the ingested resumes.";
    private static final String FAIRNESS_GUARDRAIL_MESSAGE = "I can't rank or filter candidates by protected attributes (such as age, gender, race, ethnicity, religion, disability, marital status, or nationality). Please ask using job-relevant skills, experience, and responsibilities.";
    private static final String SECURITY_GUARDRAIL_MESSAGE = "I can't help with requests to bypass instructions, reveal hidden prompts, or expose secrets/credentials. Ask about candidate skills, role fit, and experience instead.";
    private static final String PROMPT_TEMPLATE = """
        Answer the question based only on the following resume excerpts.
        List people or skills mentioned when relevant. If the context does not contain \
        relevant information, say so clearly.
        Never infer or prioritize protected attributes (age, gender, race, ethnicity, religion, disability, nationality, marital status).
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

    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "from", "that", "this", "have", "has", "are",
            "was", "were", "into", "about", "which", "when", "where", "who", "what",
            "how", "why", "can", "you", "their", "they", "them", "your", "our", "all",
            "any", "not", "use", "using", "more", "than", "over", "under", "does", "did",
            "been", "will", "would", "could", "should", "between", "after", "before", "through",
            "need", "needs", "want", "wants", "looking", "search", "find", "show", "list",
            "candidate", "candidates", "profile", "profiles", "resume", "resumes"
    );

    private static final Set<String> SENSITIVE_ATTRIBUTE_TERMS = Set.of(
            "age", "gender", "sex", "race", "ethnicity", "religion", "disabled", "disability",
            "marital", "married", "pregnant", "pregnancy", "nationality", "national origin",
            "female", "male", "woman", "women", "man", "men"
    );

    private static final Set<String> DISALLOWED_BIAS_INTENT_TERMS = Set.of(
            "rank", "filter", "prefer", "best", "hire", "select", "shortlist", "choose", "exclude"
    );

    private static final Set<String> HIRING_INTENT_TERMS = Set.of(
            "candidate", "candidates", "hire", "hiring", "recruit", "recruiting", "shortlist",
            "select", "choose", "filter", "rank", "prefer", "exclude", "screen", "match",
            "find", "list", "show", "recommend", "interview"
    );

    private static final List<Pattern> SENSITIVE_ATTRIBUTE_PATTERNS = List.of(
            Pattern.compile("\\b(age|gender|sex|race|ethnicity|religion|nationality|national origin|marital|pregnan\\w*|disabilit\\w*)\\b"),
            Pattern.compile("\\b(female|male|woman|women|man|men|mother|father)\\b"),
            Pattern.compile("\\b(christian|muslim|jewish|hindu|buddhist)\\b"),
            Pattern.compile("\\b(young|older|old)\\b"),
            Pattern.compile("\\b(over|under)\\s+\\d{2}\\b")
    );

    private static final List<Pattern> PROMPT_INJECTION_PATTERNS = List.of(
            Pattern.compile("\\b(ignore|disregard|override|bypass)\\b.{0,80}\\b(instruction|prompt|guardrail|policy|safety)\\b"),
            Pattern.compile("\\b(reveal|show|print|dump|display)\\b.{0,80}\\b(system prompt|developer message|hidden prompt|hidden instruction|internal instruction)\\b"),
            Pattern.compile("\\b(jailbreak|prompt injection|dan mode)\\b")
    );

    private static final List<Pattern> SECRET_EXFILTRATION_PATTERNS = List.of(
            Pattern.compile("\\b(reveal|show|print|dump|display|leak|expose|return)\\b.{0,120}\\b(api key|access token|secret key|private key|password|credential|openai_api_key|openai key|\\.env|environment variable)\\b"),
            Pattern.compile("\\b(api key|access token|secret key|private key|password|credential|openai_api_key|openai key|\\.env|environment variable)\\b.{0,120}\\b(reveal|show|print|dump|display|leak|expose|return)\\b")
    );

    private final EmbeddingStore<TextSegment> embeddingStore;
    private final EmbeddingModel embeddingModel;
    private final ChatModel chatModel;
    private final CandidateProfileService candidateProfileService;
    private final QueryFeedbackService queryFeedbackService;
    private final ObservabilityService observabilityService;
    private final int defaultMaxResults;
    private final int maxAllowedResults;
    private final double minScore;
    private final String noResultsAnswer;
    private final String retrieverStaticFilterKey;
    private final String retrieverStaticFilterValue;
    private final String retrieverDynamicFilterKey;
    private final QueryResultCache queryResultCache;
    private final Map<QueryCacheKey, CompletableFuture<QueryComputation>> queryComputationInFlight = new ConcurrentHashMap<>();

    RagService(EmbeddingStore<TextSegment> embeddingStore,
               EmbeddingModel embeddingModel,
               ChatModel chatModel) {
        this(
                embeddingStore,
                embeddingModel,
                chatModel,
                null,
                null,
                null,
                DEFAULT_MAX_RESULTS,
                DEFAULT_MAX_ALLOWED_RESULTS,
                DEFAULT_MIN_SCORE,
                DEFAULT_NO_RESULTS_ANSWER,
                null,
                null,
                null);
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
                null,
                null,
                maxResults,
                DEFAULT_MAX_ALLOWED_RESULTS,
                minScore,
                noResultsAnswer,
                null,
                null,
                null);
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
                null,
                null,
                maxResults,
                maxAllowedResults,
                minScore,
                noResultsAnswer,
                null,
                null,
                null);
    }

    RagService(EmbeddingStore<TextSegment> embeddingStore,
               EmbeddingModel embeddingModel,
               ChatModel chatModel,
               int maxResults,
               int maxAllowedResults,
               double minScore,
               String noResultsAnswer,
               String retrieverStaticFilterKey,
               String retrieverStaticFilterValue,
               String retrieverDynamicFilterKey) {
        this(
                embeddingStore,
                embeddingModel,
                chatModel,
                null,
                null,
                null,
                maxResults,
                maxAllowedResults,
                minScore,
                noResultsAnswer,
                retrieverStaticFilterKey,
                retrieverStaticFilterValue,
                retrieverDynamicFilterKey);
    }

    @Autowired
    public RagService(EmbeddingStore<TextSegment> embeddingStore,
                      EmbeddingModel embeddingModel,
                      ChatModel chatModel,
                      CandidateProfileService candidateProfileService,
                      QueryFeedbackService queryFeedbackService,
                      ObservabilityService observabilityService,
                      @Value("${app.rag.max-results:50}") int maxResults,
                      @Value("${app.rag.max-allowed-results:200}") int maxAllowedResults,
                      @Value("${app.rag.min-score:0.75}") double minScore,
                      @Value("${app.rag.no-results-answer:I couldn't find relevant information in the ingested resumes.}") String noResultsAnswer,
                      @Value("${app.rag.retriever.static-filter.metadata-key:}") String retrieverStaticFilterKey,
                      @Value("${app.rag.retriever.static-filter.metadata-value:}") String retrieverStaticFilterValue,
                      @Value("${app.rag.retriever.dynamic-filter.metadata-key:}") String retrieverDynamicFilterKey) {
        this.embeddingStore = embeddingStore;
        this.embeddingModel = embeddingModel;
        this.chatModel = chatModel;
        this.candidateProfileService = candidateProfileService;
        this.queryFeedbackService = queryFeedbackService;
        this.observabilityService = observabilityService;
        int configuredDefault = maxResults > 0 ? maxResults : DEFAULT_MAX_RESULTS;
        int configuredMaxAllowed = maxAllowedResults > 0 ? maxAllowedResults : DEFAULT_MAX_ALLOWED_RESULTS;
        this.maxAllowedResults = Math.max(configuredDefault, configuredMaxAllowed);
        this.defaultMaxResults = Math.min(configuredDefault, this.maxAllowedResults);
        this.minScore = Math.max(0.0d, minScore);
        this.noResultsAnswer = noResultsAnswer != null && !noResultsAnswer.isBlank()
                ? noResultsAnswer.trim()
                : DEFAULT_NO_RESULTS_ANSWER;
        this.retrieverStaticFilterKey = cleanNullable(retrieverStaticFilterKey);
        this.retrieverStaticFilterValue = cleanNullable(retrieverStaticFilterValue);
        this.retrieverDynamicFilterKey = cleanNullable(retrieverDynamicFilterKey);
        this.queryResultCache = new QueryResultCache(DEFAULT_QUERY_CACHE_MAX_ENTRIES, DEFAULT_QUERY_CACHE_TTL_MILLIS);
    }

    public QueryResponse query(String question) {
        return query(question, null, null, null, null, null);
    }

    public QueryResponse query(String question, Integer requestedMaxResults, Double requestedMinScore) {
        return query(question, requestedMaxResults, requestedMinScore, null, null, null);
    }

    public QueryResponse query(
            String question,
            Integer requestedMaxResults,
            Double requestedMinScore,
            Integer requestedPage,
            Integer requestedPageSize) {
        return query(question, requestedMaxResults, requestedMinScore, requestedPage, requestedPageSize, null);
    }

    public QueryResponse query(
            String question,
            Integer requestedMaxResults,
            Double requestedMinScore,
            Integer requestedPage,
            Integer requestedPageSize,
            Boolean requestedUseFeedbackTuning) {
        return query(
                question,
                requestedMaxResults,
                requestedMinScore,
                requestedPage,
                requestedPageSize,
                requestedUseFeedbackTuning,
                null);
    }

    public QueryResponse query(
            String question,
            Integer requestedMaxResults,
            Double requestedMinScore,
            Integer requestedPage,
            Integer requestedPageSize,
            Boolean requestedUseFeedbackTuning,
            String scopeId) {
        long startedAt = System.nanoTime();
        try {
            int effectiveMaxResults = requestedMaxResults != null
                    ? Math.min(Math.max(requestedMaxResults, 1), maxAllowedResults)
                    : defaultMaxResults;
            double effectiveMinScore = requestedMinScore != null
                    ? Math.max(0.0d, requestedMinScore)
                    : minScore;
            if (Boolean.TRUE.equals(requestedUseFeedbackTuning) && queryFeedbackService != null) {
                effectiveMinScore = queryFeedbackService.recommendMinScore(effectiveMinScore);
            }
            final double effectiveThreshold = effectiveMinScore;
            int effectivePage = requestedPage != null ? Math.max(1, requestedPage) : 1;
            int effectivePageSize = requestedPageSize != null
                    ? Math.min(Math.max(1, requestedPageSize), DEFAULT_MAX_PAGE_SIZE)
                    : DEFAULT_PAGE_SIZE;

            String normalizedQuestion = question == null ? "" : question.trim();
            GuardrailDecision guardrailDecision = evaluateGuardrails(normalizedQuestion);
            if (guardrailDecision.blocked()) {
                QueryResponse response = QueryResponse.of(
                        guardrailDecision.answer(),
                        List.of(),
                        effectivePage,
                        effectivePageSize,
                        0,
                        new QueryExplainability(List.of(), List.of(), 0.0d));
                recordQuerySuccess(startedAt, 0);
                return response;
            }

            String cleanedScopeId = cleanNullable(scopeId);
            QueryCacheKey cacheKey = new QueryCacheKey(
                    normalize(normalizedQuestion),
                    effectiveMaxResults,
                    toScoreKey(effectiveThreshold),
                    cleanedScopeId != null ? cleanedScopeId : ""
            );
            QueryComputation computation = getOrComputeQueryComputation(
                    cacheKey,
                    () -> computeQueryComputation(normalizedQuestion, effectiveMaxResults, effectiveThreshold, cleanedScopeId)
            );

            List<RankedMatch> uniqueMatches = computation.uniqueMatches();
            int totalSources = uniqueMatches.size();
            int startIndex = Math.min((effectivePage - 1) * effectivePageSize, totalSources);
            int endIndex = Math.min(startIndex + effectivePageSize, totalSources);

            List<QueryResponse.SourceSegment> sources = new ArrayList<>();
            for (int i = startIndex; i < endIndex; i++) {
                RankedMatch rankedMatch = uniqueMatches.get(i);
                String source = rankedMatch.source();
                String candidateId = rankedMatch.candidateId();

                sources.add(new QueryResponse.SourceSegment(
                        rankedMatch.text(),
                        source,
                        rankedMatch.hybridScore(),
                        i + 1,
                        candidateId,
                        rankedMatch.vectorScore(),
                        rankedMatch.keywordScore(),
                        rankedMatch.matchedTerms(),
                        rankedMatch.missingTerms()
                ));
            }

            QueryResponse response = QueryResponse.of(
                    computation.answer(),
                    sources,
                    effectivePage,
                    effectivePageSize,
                    totalSources,
                    computation.explainability()
            );
            recordQuerySuccess(startedAt, totalSources);
            return response;
        } catch (RuntimeException ex) {
            if (observabilityService != null) {
                observabilityService.recordQueryError();
            }
            throw ex;
        }
    }

    private void recordQuerySuccess(long startedAt, int totalSources) {
        if (observabilityService == null) {
            return;
        }
        long latencyMs = Math.max(0L, (System.nanoTime() - startedAt) / 1_000_000L);
        observabilityService.recordQuery(latencyMs, totalSources);
    }

    private QueryComputation computeQueryComputation(
            String normalizedQuestion,
            int effectiveMaxResults,
            double effectiveThreshold,
            String scopeId) {
        List<String> queryTerms = extractQueryTerms(normalizedQuestion);
        List<RankedMatch> rankedMatches = retrieveContents(normalizedQuestion, effectiveMaxResults, scopeId).stream()
                .map(content -> toRankedMatch(content, queryTerms))
                .sorted((a, b) -> Double.compare(b.hybridScore(), a.hybridScore()))
                .filter(match -> match.hybridScore() >= effectiveThreshold)
                .toList();
        List<RankedMatch> uniqueMatches = deduplicateMatches(
                rankedMatches.stream()
                        .map(this::resolveCandidateIdentity)
                        .toList()
        );
        if (uniqueMatches.isEmpty()) {
            return new QueryComputation(
                    noResultsAnswer,
                    List.of(),
                    new QueryExplainability(List.of(), queryTerms, 0.0d)
            );
        }

        List<RankedMatch> contextMatches = uniqueMatches.stream()
                .limit(MAX_CONTEXT_SEGMENTS)
                .toList();
        String context = contextMatches.stream()
                .map(RankedMatch::text)
                .collect(java.util.stream.Collectors.joining("\n\n"));
        QueryExplainability explainability = buildExplainability(queryTerms, uniqueMatches);
        String prompt = String.format(PROMPT_TEMPLATE, context, normalizedQuestion);
        String answer = generateAnswerWithFallback(prompt, normalizedQuestion, queryTerms, contextMatches);
        return new QueryComputation(answer, uniqueMatches, explainability);
    }

    private QueryComputation getOrComputeQueryComputation(QueryCacheKey cacheKey, Supplier<QueryComputation> computer) {
        long now = System.currentTimeMillis();
        QueryComputation cached = queryResultCache.get(cacheKey, now);
        if (cached != null) {
            return cached;
        }

        CompletableFuture<QueryComputation> newFuture = new CompletableFuture<>();
        CompletableFuture<QueryComputation> existingFuture = queryComputationInFlight.putIfAbsent(cacheKey, newFuture);
        if (existingFuture == null) {
            try {
                QueryComputation computed = computer.get();
                queryResultCache.put(cacheKey, computed, now);
                newFuture.complete(computed);
                return computed;
            } catch (RuntimeException ex) {
                newFuture.completeExceptionally(ex);
                throw ex;
            } catch (Error err) {
                newFuture.completeExceptionally(err);
                throw err;
            } finally {
                queryComputationInFlight.remove(cacheKey, newFuture);
            }
        }

        try {
            return existingFuture.join();
        } catch (CompletionException ex) {
            Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new RuntimeException(cause);
        }
    }

    private List<Content> retrieveContents(String question, int maxResults, String scopeId) {
        ContentRetriever retriever = buildRetriever(maxResults);
        return retriever.retrieve(buildRetrieverQuery(question, scopeId));
    }

    private ContentRetriever buildRetriever(int maxResults) {
        EmbeddingStoreContentRetriever.EmbeddingStoreContentRetrieverBuilder builder = EmbeddingStoreContentRetriever.builder()
                .embeddingStore(embeddingStore)
                .embeddingModel(embeddingModel)
                .maxResults(maxResults)
                .minScore(0.0d);

        Filter staticFilter = buildStaticFilter();
        Function<Query, Filter> filterProvider = buildFilterProvider(staticFilter);
        if (filterProvider != null) {
            builder.dynamicFilter(filterProvider);
        }

        return builder.build();
    }

    private Function<Query, Filter> buildFilterProvider(Filter staticFilter) {
        if (staticFilter == null && retrieverDynamicFilterKey == null) {
            return null;
        }
        return query -> {
            Filter dynamicFilter = buildScopeFilter(query);
            if (staticFilter == null) {
                return dynamicFilter;
            }
            if (dynamicFilter == null) {
                return staticFilter;
            }
            return new And(staticFilter, dynamicFilter);
        };
    }

    private Filter buildStaticFilter() {
        if (retrieverStaticFilterKey == null || retrieverStaticFilterValue == null) {
            return null;
        }
        return metadataKey(retrieverStaticFilterKey).isEqualTo(retrieverStaticFilterValue);
    }

    private Filter buildScopeFilter(Query query) {
        if (retrieverDynamicFilterKey == null || query == null || query.metadata() == null) {
            return null;
        }
        Object chatMemoryId = query.metadata().chatMemoryId();
        if (chatMemoryId == null) {
            return null;
        }
        String scopeValue = cleanNullable(chatMemoryId.toString());
        if (scopeValue == null) {
            return null;
        }
        return metadataKey(retrieverDynamicFilterKey).isEqualTo(scopeValue);
    }

    private static Query buildRetrieverQuery(String question, String scopeId) {
        String queryText = question == null ? "" : question;
        String cleanedScopeId = cleanNullable(scopeId);
        if (cleanedScopeId == null) {
            return Query.from(queryText);
        }
        Metadata metadata = Metadata.from(UserMessage.from(queryText), cleanedScopeId, List.of());
        return Query.from(queryText, metadata);
    }

    private static String cleanNullable(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isBlank() ? null : trimmed;
    }

    private String generateAnswerWithFallback(
            String prompt,
            String question,
            List<String> queryTerms,
            List<RankedMatch> contextMatches) {
        try {
            return chatModel.chat(prompt);
        } catch (RuntimeException ex) {
            log.warn("Falling back to extractive query answer due to chat model failure: {}", ex.getMessage());
            return buildExtractiveFallbackAnswer(question, queryTerms, contextMatches);
        }
    }

    private static String buildExtractiveFallbackAnswer(
            String question,
            List<String> queryTerms,
            List<RankedMatch> contextMatches) {
        List<RankedMatch> top = contextMatches == null ? List.of() : contextMatches.stream().limit(3).toList();
        List<String> topSources = top.stream()
                .map(RankedMatch::source)
                .filter(source -> source != null && !source.isBlank())
                .distinct()
                .toList();
        String sourceSummary = topSources.isEmpty()
                ? "retrieved resume excerpts"
                : String.join(", ", topSources);

        Set<String> matchedTerms = new LinkedHashSet<>();
        for (RankedMatch match : top) {
            matchedTerms.addAll(match.matchedTerms());
        }
        String matchedSummary = matchedTerms.isEmpty()
                ? "No explicit keyword overlap was detected; results rely mostly on semantic similarity."
                : "Matched terms: " + String.join(", ", matchedTerms.stream().limit(6).toList()) + ".";

        String followUp = (queryTerms == null || queryTerms.isEmpty())
                ? "Refine the query with must-have skills and target role."
                : "Refine by adding constraints for missing terms: "
                + String.join(", ", queryTerms.stream().limit(5).toList()) + ".";

        return "ANSWER:\n"
                + "Found " + top.size() + " top matching excerpts for \"" + question + "\" from " + sourceSummary + ".\n\n"
                + "KEY_FINDINGS:\n"
                + "- " + matchedSummary + "\n"
                + "- Ranking is based on hybrid vector and keyword scoring.\n\n"
                + "LIMITATIONS:\n"
                + "- LLM summarization timed out; this is an extractive fallback answer.\n\n"
                + "NEXT_STEPS:\n"
                + "- " + followUp;
    }

    private static RankedMatch toRankedMatch(Content content, List<String> queryTerms) {
        TextSegment textSegment = content != null ? content.textSegment() : null;
        String text = textSegment != null && textSegment.text() != null ? textSegment.text() : "";
        TermMatch termMatch = scoreTermOverlap(text, queryTerms);
        double vectorScore = readRetrieverScore(content);
        double hybridScore = clampScore(vectorScore * VECTOR_WEIGHT + termMatch.keywordScore() * KEYWORD_WEIGHT);
        return new RankedMatch(
                text,
                extractSource(textSegment),
                extractCandidateId(textSegment),
                vectorScore,
                termMatch.keywordScore(),
                hybridScore,
                termMatch.matchedTerms(),
                termMatch.missingTerms()
        );
    }

    private RankedMatch resolveCandidateIdentity(RankedMatch match) {
        String candidateId = cleanNullable(match.candidateId());
        if (candidateId != null || candidateProfileService == null || match.source().isBlank()) {
            return match;
        }
        String resolvedCandidateId = candidateProfileService.getBySourceFilename(match.source())
                .map(profile -> cleanNullable(profile.id()))
                .orElse(null);
        if (resolvedCandidateId == null) {
            return match;
        }
        return new RankedMatch(
                match.text(),
                match.source(),
                resolvedCandidateId,
                match.vectorScore(),
                match.keywordScore(),
                match.hybridScore(),
                match.matchedTerms(),
                match.missingTerms()
        );
    }

    private static List<RankedMatch> deduplicateMatches(List<RankedMatch> matches) {
        if (matches == null || matches.isEmpty()) {
            return List.of();
        }
        Map<String, RankedMatch> bestByKey = new LinkedHashMap<>();
        int fallbackIndex = 0;
        for (RankedMatch match : matches) {
            String key;
            if (!match.candidateId().isBlank()) {
                key = "candidate:" + match.candidateId().toLowerCase(Locale.ROOT);
            } else if (!match.source().isBlank()) {
                key = "source:" + match.source().toLowerCase(Locale.ROOT);
            } else {
                String text = normalize(match.text());
                key = text.isBlank() ? "fallback:" + (fallbackIndex++) : "text:" + text;
            }
            bestByKey.putIfAbsent(key, match);
        }
        return List.copyOf(bestByKey.values());
    }

    private static QueryExplainability buildExplainability(List<String> queryTerms, List<RankedMatch> matches) {
        Set<String> matched = new LinkedHashSet<>();
        for (RankedMatch match : matches) {
            matched.addAll(match.matchedTerms());
        }
        List<String> matchedTerms = matched.stream().limit(MAX_EXPLAINABILITY_TERMS).toList();

        List<String> missingTerms = queryTerms.stream()
                .filter(term -> !matched.contains(term))
                .limit(MAX_EXPLAINABILITY_TERMS)
                .toList();

        double confidence = matches.stream()
                .limit(3)
                .mapToDouble(RankedMatch::hybridScore)
                .average()
                .orElse(0.0d);

        return new QueryExplainability(matchedTerms, missingTerms, clampScore(confidence));
    }

    private static TermMatch scoreTermOverlap(String text, List<String> queryTerms) {
        if (queryTerms == null || queryTerms.isEmpty()) {
            return new TermMatch(0.0d, List.of(), List.of());
        }
        String normalizedText = normalize(text);
        List<String> matched = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String term : queryTerms) {
            if (normalizedText.contains(term)) {
                matched.add(term);
            } else {
                missing.add(term);
            }
        }
        double keywordScore = (double) matched.size() / queryTerms.size();
        return new TermMatch(clampScore(keywordScore), List.copyOf(matched), List.copyOf(missing));
    }

    private static List<String> extractQueryTerms(String question) {
        if (question == null || question.isBlank()) {
            return List.of();
        }
        Set<String> terms = new LinkedHashSet<>();
        String[] tokens = normalize(question).split("[^a-z0-9+/#.-]+");
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String term = token.trim();
            if (term.length() < 3 || STOP_WORDS.contains(term) || isSensitiveTerm(term)) {
                continue;
            }
            terms.add(term);
            if (terms.size() >= MAX_EXPLAINABILITY_TERMS) {
                break;
            }
        }
        return List.copyOf(terms);
    }

    private static boolean isSensitiveBiasRequest(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String lower = normalize(question);
        boolean hasSensitiveAttribute = containsSensitiveAttribute(lower);
        if (!hasSensitiveAttribute) {
            return false;
        }
        boolean hasBiasVerbIntent = containsAnyToken(lower, DISALLOWED_BIAS_INTENT_TERMS);
        boolean hasHiringIntent = containsAnyToken(lower, HIRING_INTENT_TERMS);
        return hasBiasVerbIntent || hasHiringIntent;
    }

    private static boolean isPromptInjectionAttempt(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        return matchesAnyPattern(normalize(question), PROMPT_INJECTION_PATTERNS);
    }

    private static boolean isSecretExfiltrationRequest(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        return matchesAnyPattern(normalize(question), SECRET_EXFILTRATION_PATTERNS);
    }

    private static GuardrailDecision evaluateGuardrails(String question) {
        if (isSensitiveBiasRequest(question)) {
            return GuardrailDecision.blocked(structuredFairnessPolicyAnswer());
        }
        if (isPromptInjectionAttempt(question) || isSecretExfiltrationRequest(question)) {
            return GuardrailDecision.blocked(structuredSecurityPolicyAnswer());
        }
        return GuardrailDecision.allow();
    }

    private static boolean isSensitiveTerm(String term) {
        if (term == null || term.isBlank()) {
            return false;
        }
        return containsSensitiveAttribute(normalize(term));
    }

    private static String structuredFairnessPolicyAnswer() {
        return "ANSWER:\n"
                + FAIRNESS_GUARDRAIL_MESSAGE
                + "\n\nKEY_FINDINGS:\n"
                + "- Protected-attribute based ranking is not supported.\n"
                + "- Skill, experience, and role-fit based filtering is available.\n\n"
                + "LIMITATIONS:\n"
                + "- No source evidence is returned for disallowed requests.\n\n"
                + "NEXT_STEPS:\n"
                + "- Rephrase the query using required skills, years of experience, and target role.";
    }

    private static String structuredSecurityPolicyAnswer() {
        return "ANSWER:\n"
                + SECURITY_GUARDRAIL_MESSAGE
                + "\n\nKEY_FINDINGS:\n"
                + "- Requests to bypass system/developer instructions are blocked.\n"
                + "- Requests to expose secrets or credentials are blocked.\n\n"
                + "LIMITATIONS:\n"
                + "- No source evidence is returned for blocked security requests.\n\n"
                + "NEXT_STEPS:\n"
                + "- Rephrase the query around candidate role, skills, and experience criteria.";
    }

    private static boolean containsSensitiveAttribute(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        if (containsAnyToken(text, SENSITIVE_ATTRIBUTE_TERMS)) {
            return true;
        }
        return matchesAnyPattern(text, SENSITIVE_ATTRIBUTE_PATTERNS);
    }

    private static boolean containsAnyToken(String text, Set<String> tokens) {
        if (text == null || text.isBlank() || tokens == null || tokens.isEmpty()) {
            return false;
        }
        for (String token : tokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            Pattern tokenPattern = Pattern.compile("\\b" + Pattern.quote(token.toLowerCase(Locale.ROOT)) + "\\b");
            if (tokenPattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAnyPattern(String text, List<Pattern> patterns) {
        if (text == null || text.isBlank() || patterns == null || patterns.isEmpty()) {
            return false;
        }
        for (Pattern pattern : patterns) {
            if (pattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private static String extractSource(TextSegment segment) {
        if (segment == null || segment.metadata() == null) {
            return "";
        }
        String source = segment.metadata().getString("source");
        return source != null ? source.trim() : "";
    }

    private static String extractCandidateId(TextSegment segment) {
        if (segment == null || segment.metadata() == null) {
            return "";
        }
        String candidateId = segment.metadata().getString("candidate_id");
        return candidateId != null ? candidateId.trim() : "";
    }

    private static double readRetrieverScore(Content content) {
        if (content == null || content.metadata() == null) {
            return 0.0d;
        }
        Object score = content.metadata().get(ContentMetadata.SCORE);
        if (score instanceof Number number) {
            return clampScore(number.doubleValue());
        }
        return 0.0d;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static double clampScore(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static int toScoreKey(double value) {
        return (int) Math.round(clampScore(value) * 1000.0d);
    }

    private record RankedMatch(
            String text,
            String source,
            String candidateId,
            double vectorScore,
            double keywordScore,
            double hybridScore,
            List<String> matchedTerms,
            List<String> missingTerms
    ) {
    }

    private record TermMatch(
            double keywordScore,
            List<String> matchedTerms,
            List<String> missingTerms
    ) {
    }

    private record QueryComputation(
            String answer,
            List<RankedMatch> uniqueMatches,
            QueryExplainability explainability
    ) {
    }

    private record QueryCacheKey(
            String normalizedQuestion,
            int maxResults,
            int minScoreKey,
            String scopeId
    ) {
    }

    private record QueryCacheEntry(
            QueryComputation computation,
            long cachedAtMillis
    ) {
    }

    private static final class QueryResultCache {
        private final long ttlMillis;
        private final Map<QueryCacheKey, QueryCacheEntry> entries;

        private QueryResultCache(int maxEntries, long ttlMillis) {
            int safeMaxEntries = Math.max(1, maxEntries);
            this.ttlMillis = Math.max(0L, ttlMillis);
            this.entries = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<QueryCacheKey, QueryCacheEntry> eldest) {
                    return size() > safeMaxEntries;
                }
            });
        }

        private QueryComputation get(QueryCacheKey key, long nowMillis) {
            if (ttlMillis <= 0L) {
                return null;
            }
            QueryCacheEntry entry = entries.get(key);
            if (entry == null) {
                return null;
            }
            if (nowMillis - entry.cachedAtMillis() > ttlMillis) {
                entries.remove(key);
                return null;
            }
            return entry.computation();
        }

        private void put(QueryCacheKey key, QueryComputation computation, long nowMillis) {
            if (ttlMillis <= 0L) {
                return;
            }
            entries.put(key, new QueryCacheEntry(computation, nowMillis));
        }
    }

    private record GuardrailDecision(boolean blocked, String answer) {
        static GuardrailDecision allow() {
            return new GuardrailDecision(false, "");
        }

        static GuardrailDecision blocked(String answer) {
            return new GuardrailDecision(true, answer);
        }
    }
}
