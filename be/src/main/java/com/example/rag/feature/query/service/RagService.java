package com.example.rag.feature.query.service;

import com.example.rag.feature.candidate.model.CandidateProfile;
import com.example.rag.feature.candidate.service.CandidateProfileService;
import com.example.rag.feature.query.model.QueryExplainability;
import com.example.rag.feature.query.model.QueryResponse;
import com.example.rag.feature.feedback.service.QueryFeedbackService;
import com.example.rag.feature.metrics.service.ObservabilityService;
import com.example.rag.feature.role.domain.TechnicalRoleCatalog;
import com.example.rag.feature.role.domain.TechnicalRoleCatalog.RoleDefinition;
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
import java.util.Comparator;
import java.util.function.Function;
import java.util.function.Supplier;
import java.text.Normalizer;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

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
    private static final int CANDIDATE_EXISTENCE_MAX_RESULTS = 50;
    private static final int CANDIDATE_FILTER_MAX_RESULTS = 100;
    private static final int MAX_CONTEXT_SEGMENTS = 20;
    private static final int MAX_EXPLAINABILITY_TERMS = 8;
    private static final double DEFAULT_MIN_SCORE = 0.75d;
    private static final double VECTOR_WEIGHT = 0.8d;
    private static final double KEYWORD_WEIGHT = 0.2d;
    private static final String DEFAULT_NO_RESULTS_ANSWER =
            "I couldn't find relevant information in the ingested resumes.";
    private static final String FAIRNESS_GUARDRAIL_MESSAGE = "I can't rank or filter candidates by protected attributes (such as age, gender, race, ethnicity, religion, disability, marital status, or nationality). Please ask using job-relevant skills, experience, and responsibilities.";
    private static final String SECURITY_GUARDRAIL_MESSAGE = "I can't help with requests to bypass instructions, reveal hidden prompts, or expose secrets/credentials. Ask about candidate skills, role fit, and experience instead.";
    private static final String CANDIDATE_EXISTENCE_INTENT_PROMPT = """
            You are an intent parser for a candidate search system.
            Determine whether the user asks if a specific person exists in candidate records.
            Return ONLY valid JSON with this exact schema:
            {"isExistenceQuery":true,"targetName":"<full person name>"}
            or
            {"isExistenceQuery":false,"targetName":""}
            Rules:
            - Extract only person names.
            - If the question is about skills, roles, location, years, or ranking, return isExistenceQuery=false.
            - If uncertain, return isExistenceQuery=false.
            User question: %s
            """;
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

    private static final Set<String> CANDIDATE_EXISTENCE_NOISE_TERMS = Set.of(
            "candidate", "candidates", "database", "db", "system", "pool", "our", "the", "in", "on",
            "skills", "skill", "role", "roles", "engineer", "developer", "qa", "devops", "manager"
    );

    private static final Set<String> CANDIDATE_FILTER_INTENT_TERMS = Set.of(
            "candidate", "candidates", "profile", "profiles", "who", "which", "list", "show", "find", "need", "looking"
    );
    private static final Set<String> LOCATION_STOP_TERMS = Set.of(
            "database", "db", "system", "candidate", "candidates", "our", "the", "with", "who", "that", "have", "has"
    );
    private static final List<RoleDefinition> ROLE_DEFINITIONS = TechnicalRoleCatalog.roleDefinitions();

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

    private static final Pattern CANDIDATE_EXISTENCE_DATABASE_PATTERN = Pattern.compile(
            "^\\s*(?:is|are|do\\s+we\\s+have|does\\s+our\\s+database\\s+have|can\\s+you\\s+find)\\s+(.+?)\\s+(?:in|on)\\s+(?:our\\s+)?(?:database|db|system|candidate\\s+database|talent\\s+pool|records?|candidate\\s+records?|indexed\\s+records?)\\s*\\??\\s*$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CANDIDATE_EXISTENCE_HAVE_PATTERN = Pattern.compile(
            "^\\s*(?:do\\s+we\\s+have|can\\s+you\\s+find)\\s+(.+?)\\s*\\??\\s*$",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CANDIDATE_EXISTENCE_START_PATTERN = Pattern.compile(
            "^\\s*(?:is|are|do\\s+we\\s+have|does\\s+our\\s+\\w+\\s+have|does\\s+.+\\s+exist|can\\s+you\\s+(?:find|check|verify)|could\\s+you\\s+(?:find|check|verify)|is\\s+there|are\\s+there|have\\s+we\\s+ingested|did\\s+we\\s+ingest)\\b",
            Pattern.CASE_INSENSITIVE
    );
    private static final List<Pattern> CANDIDATE_EXISTENCE_REFERENCE_PATTERNS = List.of(
            Pattern.compile("\\b(database|db|system|records?|datastore|index|indexed)\\b"),
            Pattern.compile("\\b(candidate\\s+records?|talent\\s+pool|on\\s+file)\\b")
    );
    private static final Pattern JSON_TARGET_NAME_PATTERN = Pattern.compile(
            "\"targetName\"\\s*:\\s*\"([^\"]*)\"",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern JSON_EXISTENCE_FLAG_PATTERN = Pattern.compile(
            "\"isExistenceQuery\"\\s*:\\s*(true|false)",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern CHAT_CURRENT_QUESTION_PATTERN = Pattern.compile(
            "(?is)\\bcurrent\\s+question\\s*:\\s*(.+)$"
    );
    private static final Pattern YEARS_MIN_PATTERN = Pattern.compile(
            "(?:at\\s+least|min(?:imum)?|>=|over|more\\s+than)\\s*(\\d{1,2})\\s*\\+?\\s*years?",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern YEARS_MAX_PATTERN = Pattern.compile(
            "(?:at\\s+most|max(?:imum)?|<=|under|less\\s+than)\\s*(\\d{1,2})\\s*\\+?\\s*years?",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern YEARS_GENERIC_PATTERN = Pattern.compile(
            "(\\d{1,2})\\s*\\+?\\s*years?",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern LOCATION_PATTERN = Pattern.compile(
            "(?:located\\s+in|based\\s+in|from|in)\\s+([a-zA-Z][a-zA-Z\\s,.'-]{1,40})",
            Pattern.CASE_INSENSITIVE
    );
    private static final Pattern NAMED_PATTERN = Pattern.compile(
            "(?:named|name|candidate)\\s+([a-zA-Z][a-zA-Z\\s.'-]{2,60})",
            Pattern.CASE_INSENSITIVE
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
            String intentQuestion = extractIntentQuestion(normalizedQuestion);
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

            CandidateExistenceIntent candidateExistenceIntent = resolveCandidateExistenceIntent(intentQuestion);
            if (candidateExistenceIntent != null && candidateProfileService != null) {
                QueryResponse response = answerCandidateExistence(
                        candidateExistenceIntent,
                        effectivePage,
                        effectivePageSize
                );
                recordQuerySuccess(startedAt, response.totalSources());
                return response;
            }

            CandidateFilterIntent candidateFilterIntent = parseCandidateFilterIntent(intentQuestion);
            if (candidateFilterIntent != null && candidateProfileService != null) {
                QueryResponse response = answerCandidateFilterIntent(
                        candidateFilterIntent,
                        effectivePage,
                        effectivePageSize
                );
                if (response.totalSources() > 0 || candidateFilterIntent.hasHardConstraints()) {
                    recordQuerySuccess(startedAt, response.totalSources());
                    return response;
                }
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

    private QueryResponse answerCandidateExistence(
            CandidateExistenceIntent intent,
            int page,
            int pageSize) {
        List<CandidateMatch> matches = findCandidateMatchesByName(intent.targetName(), CANDIDATE_EXISTENCE_MAX_RESULTS);
        int total = matches.size();
        int startIndex = Math.min((page - 1) * pageSize, total);
        int endIndex = Math.min(startIndex + pageSize, total);
        List<CandidateMatch> pageMatches = startIndex < endIndex ? matches.subList(startIndex, endIndex) : List.of();

        List<QueryResponse.SourceSegment> sources = new ArrayList<>();
        for (int i = 0; i < pageMatches.size(); i++) {
            CandidateMatch match = pageMatches.get(i);
            CandidateProfile candidate = match.candidate();
            String roles = candidate.suggestedRoles() != null && !candidate.suggestedRoles().isEmpty()
                    ? String.join(", ", candidate.suggestedRoles().stream().limit(3).toList())
                    : "-";
            String skills = candidate.significantSkills() != null && !candidate.significantSkills().isEmpty()
                    ? String.join(", ", candidate.significantSkills().stream().limit(6).toList())
                    : "-";
            String details = "Candidate: " + candidate.displayName()
                    + "\nLocation: " + safeText(candidate.location())
                    + "\nEstimated years: " + (candidate.estimatedYearsExperience() != null ? candidate.estimatedYearsExperience() : "-")
                    + "\nSuggested roles: " + roles
                    + "\nTop skills: " + skills;

            sources.add(new QueryResponse.SourceSegment(
                    details,
                    safeText(candidate.sourceFilename()),
                    match.score(),
                    startIndex + i + 1,
                    safeText(candidate.id()),
                    match.score(),
                    1.0d,
                    intent.queryTokens(),
                    List.of()
            ));
        }

        String answer = total > 0
                ? buildCandidateExistencePositiveAnswer(intent.targetName(), matches)
                : buildCandidateExistenceNegativeAnswer(intent.targetName());
        double confidence = total > 0 ? matches.getFirst().score() : 0.0d;
        QueryExplainability explainability = new QueryExplainability(
                intent.queryTokens(),
                List.of(),
                clampScore(confidence)
        );
        return QueryResponse.of(answer, sources, page, pageSize, total, explainability);
    }

    private List<CandidateMatch> findCandidateMatchesByName(String targetName, int limit) {
        String normalizedTarget = normalizePersonName(targetName);
        List<String> targetTokens = personNameTokens(normalizedTarget);
        if (targetTokens.isEmpty()) {
            return List.of();
        }

        List<CandidateMatch> matches = new ArrayList<>();
        for (CandidateProfile candidate : candidateProfileService.allCandidates()) {
            if (candidate == null || candidate.displayName() == null || candidate.displayName().isBlank()) {
                continue;
            }
            double score = scoreCandidateName(candidate.displayName(), normalizedTarget, targetTokens);
            if (score <= 0.0d) {
                continue;
            }
            matches.add(new CandidateMatch(candidate, score));
        }
        return matches.stream()
                .sorted((a, b) -> {
                    int scoreCompare = Double.compare(b.score(), a.score());
                    if (scoreCompare != 0) {
                        return scoreCompare;
                    }
                    return a.candidate().displayName().compareToIgnoreCase(b.candidate().displayName());
                })
                .limit(Math.max(1, limit))
                .toList();
    }

    private static double scoreCandidateName(
            String candidateDisplayName,
            String normalizedTarget,
            List<String> targetTokens) {
        String normalizedCandidate = normalizePersonName(candidateDisplayName);
        List<String> candidateTokens = personNameTokens(normalizedCandidate);
        if (candidateTokens.isEmpty()) {
            return 0.0d;
        }

        int exactMatches = 0;
        int prefixMatches = 0;
        for (String token : targetTokens) {
            if (candidateTokens.contains(token)) {
                exactMatches++;
                continue;
            }
            boolean prefixMatch = candidateTokens.stream().anyMatch(candidateToken ->
                    candidateToken.startsWith(token) || token.startsWith(candidateToken));
            if (prefixMatch) {
                prefixMatches++;
            }
        }

        boolean containsFullTarget = normalizedCandidate.contains(normalizedTarget);
        if (!containsFullTarget && exactMatches == 0 && prefixMatches < Math.max(1, targetTokens.size() - 1)) {
            return 0.0d;
        }

        double tokenCoverage = (exactMatches + (0.5d * prefixMatches)) / Math.max(1.0d, targetTokens.size());
        double score = 0.45d + (containsFullTarget ? 0.35d : 0.0d) + (0.20d * tokenCoverage);
        return clampScore(score);
    }

    private static String buildCandidateExistencePositiveAnswer(String requestedName, List<CandidateMatch> matches) {
        CandidateMatch top = matches.getFirst();
        List<String> topNames = matches.stream()
                .limit(3)
                .map(match -> match.candidate().displayName())
                .toList();
        return "ANSWER:\n"
                + "Yes, \"" + requestedName + "\" appears in the candidate database.\n\n"
                + "KEY_FINDINGS:\n"
                + "- Top match: " + top.candidate().displayName() + ".\n"
                + "- Matching candidates found: " + matches.size() + " (" + String.join(", ", topNames) + ").\n\n"
                + "LIMITATIONS:\n"
                + "- Name matching is heuristic and may include similarly named profiles.\n\n"
                + "NEXT_STEPS:\n"
                + "- Open the candidate profile and verify contact details, skills, and role alignment.";
    }

    private static String buildCandidateExistenceNegativeAnswer(String requestedName) {
        return "ANSWER:\n"
                + "No, I could not find a candidate named \"" + requestedName + "\" in the current indexed database.\n\n"
                + "KEY_FINDINGS:\n"
                + "- No high-confidence name matches were found.\n"
                + "- This may indicate the resume has not been ingested yet or the name appears in a variant form.\n\n"
                + "LIMITATIONS:\n"
                + "- Matching uses normalized name tokens and may miss uncommon formatting variants.\n\n"
                + "NEXT_STEPS:\n"
                + "- Try a partial name, alternative spelling, or re-run ingestion for the expected resume.";
    }

    private QueryResponse answerCandidateFilterIntent(
            CandidateFilterIntent intent,
            int page,
            int pageSize) {
        List<CandidateFilterMatch> ranked = rankCandidatesForIntent(intent, CANDIDATE_FILTER_MAX_RESULTS);
        int total = ranked.size();
        int startIndex = Math.min((page - 1) * pageSize, total);
        int endIndex = Math.min(startIndex + pageSize, total);
        List<CandidateFilterMatch> pageMatches = startIndex < endIndex ? ranked.subList(startIndex, endIndex) : List.of();

        List<QueryResponse.SourceSegment> sources = new ArrayList<>();
        for (int i = 0; i < pageMatches.size(); i++) {
            CandidateFilterMatch match = pageMatches.get(i);
            CandidateProfile candidate = match.candidate();
            String details = buildCandidateFilterDetails(candidate, match);
            sources.add(new QueryResponse.SourceSegment(
                    details,
                    safeText(candidate.sourceFilename()),
                    match.score(),
                    startIndex + i + 1,
                    safeText(candidate.id()),
                    match.score(),
                    match.keywordScore(),
                    match.matchedCriteria(),
                    List.of()
            ));
        }

        String answer = total > 0
                ? buildCandidateFilterPositiveAnswer(intent, ranked)
                : buildCandidateFilterNegativeAnswer(intent);
        double confidence = total > 0 ? ranked.getFirst().score() : 0.0d;
        QueryExplainability explainability = new QueryExplainability(
                intent.queryTerms(),
                List.of(),
                clampScore(confidence)
        );
        return QueryResponse.of(answer, sources, page, pageSize, total, explainability);
    }

    private List<CandidateFilterMatch> rankCandidatesForIntent(CandidateFilterIntent intent, int limit) {
        List<CandidateFilterMatch> matches = new ArrayList<>();
        for (CandidateProfile candidate : candidateProfileService.allCandidates()) {
            CandidateFilterMatch match = scoreCandidateForIntent(candidate, intent);
            if (match != null) {
                matches.add(match);
            }
        }
        return matches.stream()
                .sorted(Comparator.comparingDouble(CandidateFilterMatch::score).reversed()
                        .thenComparing(match -> safeText(match.candidate().displayName()), String.CASE_INSENSITIVE_ORDER))
                .limit(Math.max(1, limit))
                .toList();
    }

    private CandidateFilterMatch scoreCandidateForIntent(CandidateProfile candidate, CandidateFilterIntent intent) {
        if (candidate == null) {
            return null;
        }
        String corpus = buildCandidateCorpus(candidate);
        List<String> matchedCriteria = new ArrayList<>();

        if (!intent.requiredSkills().isEmpty()) {
            boolean allSkillsMatched = true;
            for (String skill : intent.requiredSkills()) {
                if (!candidateHasSkill(candidate, corpus, skill)) {
                    allSkillsMatched = false;
                    break;
                }
                matchedCriteria.add("skill:" + skill);
            }
            if (!allSkillsMatched) {
                return null;
            }
        }

        if (!intent.requiredRoles().isEmpty()) {
            List<String> matchedRoles = intent.requiredRoles().stream()
                    .filter(role -> candidateMatchesRole(candidate, role))
                    .toList();
            if (matchedRoles.isEmpty()) {
                return null;
            }
            matchedRoles.forEach(role -> matchedCriteria.add("role:" + role));
        }

        if (intent.locationFilter() != null) {
            String location = normalize(candidate.location());
            String targetLocation = normalize(intent.locationFilter());
            if (location.isBlank() || !location.contains(targetLocation)) {
                return null;
            }
            matchedCriteria.add("location:" + intent.locationFilter());
        }

        if (intent.minYears() != null || intent.maxYears() != null) {
            Integer years = candidate.estimatedYearsExperience();
            if (years == null) {
                return null;
            }
            if (intent.minYears() != null && years < intent.minYears()) {
                return null;
            }
            if (intent.maxYears() != null && years > intent.maxYears()) {
                return null;
            }
            matchedCriteria.add("years:" + years);
        }

        if (!intent.nameTokens().isEmpty()) {
            if (!candidateMatchesNameTokens(candidate, intent.nameTokens())) {
                return null;
            }
            matchedCriteria.add("name");
        }

        double keywordScore = scoreTermOverlap(corpus, intent.queryTerms()).keywordScore();
        double yearsSignal = candidate.estimatedYearsExperience() != null
                ? clampScore(candidate.estimatedYearsExperience() / 15.0d)
                : 0.2d;
        double score = clampScore(0.55d + (0.30d * keywordScore) + (0.15d * yearsSignal));
        return new CandidateFilterMatch(candidate, score, clampScore(keywordScore), List.copyOf(matchedCriteria));
    }

    private static boolean candidateHasSkill(CandidateProfile candidate, String corpus, String skill) {
        if (candidate == null || skill == null || skill.isBlank()) {
            return false;
        }
        String canonicalSkill = TechnicalRoleCatalog.normalizeSkill(skill);
        boolean inSkillLists = candidate.skills() != null && candidate.skills().stream()
                .map(TechnicalRoleCatalog::normalizeSkill)
                .anyMatch(s -> s.equals(canonicalSkill));
        if (inSkillLists) {
            return true;
        }
        boolean inSignificantSkills = candidate.significantSkills() != null && candidate.significantSkills().stream()
                .map(TechnicalRoleCatalog::normalizeSkill)
                .anyMatch(s -> s.equals(canonicalSkill));
        if (inSignificantSkills) {
            return true;
        }
        return TechnicalRoleCatalog.containsSkillTerm(corpus, canonicalSkill);
    }

    private static boolean candidateMatchesRole(CandidateProfile candidate, String requestedRole) {
        if (candidate == null || requestedRole == null || requestedRole.isBlank()) {
            return false;
        }
        String normalizedRequested = normalize(requestedRole);
        if (normalizedRequested.isBlank()) {
            return false;
        }
        if (candidate.suggestedRoles() == null || candidate.suggestedRoles().isEmpty()) {
            return false;
        }
        for (String role : candidate.suggestedRoles()) {
            String normalizedRole = normalize(role);
            if (normalizedRole.contains(normalizedRequested) || normalizedRequested.contains(normalizedRole)) {
                return true;
            }
        }
        return false;
    }

    private static boolean candidateMatchesNameTokens(CandidateProfile candidate, List<String> nameTokens) {
        if (candidate == null || nameTokens == null || nameTokens.isEmpty()) {
            return false;
        }
        String normalizedName = normalizePersonName(candidate.displayName());
        List<String> candidateTokens = personNameTokens(normalizedName);
        if (candidateTokens.isEmpty()) {
            return false;
        }
        for (String token : nameTokens) {
            boolean matched = candidateTokens.stream().anyMatch(candidateToken ->
                    candidateToken.equals(token) || candidateToken.startsWith(token) || token.startsWith(candidateToken));
            if (!matched) {
                return false;
            }
        }
        return true;
    }

    private static String buildCandidateFilterDetails(CandidateProfile candidate, CandidateFilterMatch match) {
        String roles = candidate.suggestedRoles() != null && !candidate.suggestedRoles().isEmpty()
                ? String.join(", ", candidate.suggestedRoles().stream().limit(3).toList())
                : "-";
        String skills = candidate.significantSkills() != null && !candidate.significantSkills().isEmpty()
                ? String.join(", ", candidate.significantSkills().stream().limit(6).toList())
                : "-";
        String years = candidate.estimatedYearsExperience() != null ? String.valueOf(candidate.estimatedYearsExperience()) : "-";
        return "Candidate: " + safeText(candidate.displayName())
                + "\nLocation: " + safeText(candidate.location())
                + "\nEstimated years: " + years
                + "\nSuggested roles: " + roles
                + "\nTop skills: " + skills
                + "\nMatched criteria: " + (match.matchedCriteria().isEmpty() ? "-" : String.join(", ", match.matchedCriteria()));
    }

    private static String buildCandidateFilterPositiveAnswer(CandidateFilterIntent intent, List<CandidateFilterMatch> matches) {
        List<String> topNames = matches.stream()
                .limit(3)
                .map(match -> safeText(match.candidate().displayName()))
                .toList();
        return "ANSWER:\n"
                + "Found " + matches.size() + " candidate(s) matching your criteria.\n\n"
                + "KEY_FINDINGS:\n"
                + "- Applied filters: " + buildFilterSummary(intent) + ".\n"
                + "- Top matches: " + String.join(", ", topNames) + ".\n\n"
                + "LIMITATIONS:\n"
                + "- Results depend on extracted candidate metadata quality (skills, roles, location, and years).\n\n"
                + "NEXT_STEPS:\n"
                + "- Open the candidate profiles to verify fit details and compare source resumes.";
    }

    private static String buildCandidateFilterNegativeAnswer(CandidateFilterIntent intent) {
        return "ANSWER:\n"
                + "No candidates matched your requested criteria.\n\n"
                + "KEY_FINDINGS:\n"
                + "- Applied filters: " + buildFilterSummary(intent) + ".\n"
                + "- No profiles satisfied all required constraints.\n\n"
                + "LIMITATIONS:\n"
                + "- Some resumes may have incomplete extracted metadata.\n\n"
                + "NEXT_STEPS:\n"
                + "- Relax one filter (skills, years, role, or location) and retry.";
    }

    private static String buildFilterSummary(CandidateFilterIntent intent) {
        List<String> parts = new ArrayList<>();
        if (!intent.requiredSkills().isEmpty()) {
            parts.add("skills=" + String.join(", ", intent.requiredSkills()));
        }
        if (!intent.requiredRoles().isEmpty()) {
            parts.add("roles=" + String.join(", ", intent.requiredRoles()));
        }
        if (intent.locationFilter() != null) {
            parts.add("location=" + intent.locationFilter());
        }
        if (intent.minYears() != null || intent.maxYears() != null) {
            if (intent.minYears() != null && intent.maxYears() != null) {
                parts.add("years=" + intent.minYears() + "-" + intent.maxYears());
            } else if (intent.minYears() != null) {
                parts.add("years>=" + intent.minYears());
            } else {
                parts.add("years<=" + intent.maxYears());
            }
        }
        if (!intent.nameTokens().isEmpty()) {
            parts.add("name=" + String.join(" ", intent.nameTokens()));
        }
        return parts.isEmpty() ? "none" : String.join("; ", parts);
    }

    private static CandidateFilterIntent parseCandidateFilterIntent(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String normalizedQuestion = normalize(question);
        List<String> requiredSkills = extractRequestedSkills(question);
        List<String> requiredRoles = extractRequestedRoles(normalizedQuestion);
        String locationFilter = extractLocationFilter(question);
        Integer minYears = extractMinYears(question);
        Integer maxYears = extractMaxYears(question);
        List<String> nameTokens = extractNameTokens(question);
        List<String> queryTerms = extractQueryTerms(question);

        boolean hasFilterSignal = !requiredSkills.isEmpty()
                || !requiredRoles.isEmpty()
                || locationFilter != null
                || minYears != null
                || maxYears != null
                || !nameTokens.isEmpty();
        if (!hasFilterSignal) {
            return null;
        }

        boolean hasCandidateContext = containsAnyToken(normalizedQuestion, CANDIDATE_FILTER_INTENT_TERMS)
                || normalizedQuestion.contains("experience")
                || normalizedQuestion.contains("years");
        if (!hasCandidateContext) {
            return null;
        }

        return new CandidateFilterIntent(
                List.copyOf(requiredSkills),
                List.copyOf(requiredRoles),
                locationFilter,
                minYears,
                maxYears,
                List.copyOf(nameTokens),
                List.copyOf(queryTerms)
        );
    }

    private static List<String> extractRequestedSkills(String question) {
        String canonicalized = TechnicalRoleCatalog.canonicalizeSkillText(question);
        LinkedHashSet<String> skills = new LinkedHashSet<>();
        for (String skill : TechnicalRoleCatalog.canonicalSkills()) {
            if (TechnicalRoleCatalog.containsSkillTerm(canonicalized, skill)) {
                skills.add(skill);
            }
        }
        return List.copyOf(skills);
    }

    private static List<String> extractRequestedRoles(String normalizedQuestion) {
        LinkedHashSet<String> roles = new LinkedHashSet<>();
        for (RoleDefinition definition : ROLE_DEFINITIONS) {
            String normalizedRole = normalize(definition.title());
            if (!normalizedRole.isBlank() && normalizedQuestion.contains(normalizedRole)) {
                roles.add(definition.title());
            }
        }
        if (normalizedQuestion.contains("qa") || normalizedQuestion.contains("test engineer") || normalizedQuestion.contains("sdet")) {
            roles.add(TechnicalRoleCatalog.QA_ROLE_TITLE);
        }
        if (normalizedQuestion.contains("devops") || normalizedQuestion.contains("platform engineer") || normalizedQuestion.contains("sre")) {
            roles.add("DevOps / Platform Engineer");
        }
        if (normalizedQuestion.contains("backend engineer") || normalizedQuestion.contains("backend developer")) {
            roles.add("Backend Engineer");
        }
        if (normalizedQuestion.contains("frontend engineer") || normalizedQuestion.contains("frontend developer")) {
            roles.add("Frontend Engineer");
        }
        if (normalizedQuestion.contains("full stack") || normalizedQuestion.contains("full-stack") || normalizedQuestion.contains("fullstack")) {
            roles.add("Full-Stack Engineer");
        }
        if (normalizedQuestion.contains("tech lead") || normalizedQuestion.contains("team lead")) {
            roles.add(TechnicalRoleCatalog.TECH_LEAD_ROLE_TITLE);
        }
        if (normalizedQuestion.contains("engineering manager") || normalizedQuestion.contains("people manager")) {
            roles.add(TechnicalRoleCatalog.ENGINEERING_MANAGER_ROLE_TITLE);
        }
        if (normalizedQuestion.contains("ai engineer") || normalizedQuestion.contains("llm engineer") || normalizedQuestion.contains("rag engineer")) {
            roles.add("AI Engineer (LLM/RAG)");
        }
        return List.copyOf(roles);
    }

    private static String extractLocationFilter(String question) {
        java.util.regex.Matcher matcher = LOCATION_PATTERN.matcher(question);
        while (matcher.find()) {
            String raw = matcher.group(1);
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String cleaned = raw.replaceAll("[?.!]+$", "").trim();
            if (cleaned.length() < 3) {
                continue;
            }
            List<String> words = java.util.Arrays.stream(cleaned.toLowerCase(Locale.ROOT).split("[^a-z]+"))
                    .filter(word -> !word.isBlank())
                    .toList();
            if (words.isEmpty()) {
                continue;
            }
            boolean hasStopWord = words.stream().anyMatch(LOCATION_STOP_TERMS::contains);
            if (hasStopWord) {
                continue;
            }
            return cleaned;
        }
        return null;
    }

    private static Integer extractMinYears(String question) {
        java.util.regex.Matcher explicit = YEARS_MIN_PATTERN.matcher(question);
        if (explicit.find()) {
            return parsePositiveInt(explicit.group(1));
        }
        java.util.regex.Matcher generic = YEARS_GENERIC_PATTERN.matcher(question);
        if (generic.find()) {
            return parsePositiveInt(generic.group(1));
        }
        return null;
    }

    private static Integer extractMaxYears(String question) {
        java.util.regex.Matcher explicit = YEARS_MAX_PATTERN.matcher(question);
        if (explicit.find()) {
            return parsePositiveInt(explicit.group(1));
        }
        return null;
    }

    private static Integer parsePositiveInt(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return value > 0 ? value : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static List<String> extractNameTokens(String question) {
        java.util.regex.Matcher matcher = NAMED_PATTERN.matcher(question);
        if (!matcher.find()) {
            return List.of();
        }
        String raw = matcher.group(1);
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        String cleaned = raw.replaceAll("[?.!]+$", "").trim();
        if (!looksLikeCandidateNameTarget(cleaned)) {
            return List.of();
        }
        return personNameTokens(normalizePersonName(cleaned));
    }

    private static String buildCandidateCorpus(CandidateProfile candidate) {
        StringBuilder sb = new StringBuilder();
        appendField(sb, candidate.displayName());
        appendField(sb, candidate.location());
        if (candidate.skills() != null) {
            candidate.skills().forEach(skill -> appendField(sb, skill));
        }
        if (candidate.significantSkills() != null) {
            candidate.significantSkills().forEach(skill -> appendField(sb, skill));
        }
        if (candidate.suggestedRoles() != null) {
            candidate.suggestedRoles().forEach(role -> appendField(sb, role));
        }
        appendField(sb, candidate.preview());
        return TechnicalRoleCatalog.canonicalizeSkillText(sb.toString());
    }

    private static void appendField(StringBuilder sb, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(' ');
        }
        sb.append(value.trim());
    }

    private static CandidateExistenceIntent parseCandidateExistenceIntent(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        String compact = question.replace('\n', ' ').trim();
        String target = extractExistenceTarget(compact, CANDIDATE_EXISTENCE_DATABASE_PATTERN);
        if (target == null) {
            target = extractExistenceTarget(compact, CANDIDATE_EXISTENCE_HAVE_PATTERN);
        }
        if (target == null) {
            return null;
        }
        target = target.replaceAll("^[`\"'\\s]+|[`\"'?.!,\\s]+$", "").trim();
        if (!looksLikeCandidateNameTarget(target)) {
            return null;
        }
        List<String> tokens = personNameTokens(normalizePersonName(target));
        return tokens.isEmpty() ? null : new CandidateExistenceIntent(target, tokens);
    }

    private static String extractIntentQuestion(String question) {
        if (question == null || question.isBlank()) {
            return "";
        }
        java.util.regex.Matcher matcher = CHAT_CURRENT_QUESTION_PATTERN.matcher(question);
        if (!matcher.find()) {
            return question.trim();
        }
        String extracted = matcher.group(1);
        if (extracted == null || extracted.isBlank()) {
            return question.trim();
        }
        return extracted.trim();
    }

    private CandidateExistenceIntent resolveCandidateExistenceIntent(String question) {
        CandidateExistenceIntent deterministic = parseCandidateExistenceIntent(question);
        if (deterministic != null) {
            return deterministic;
        }
        if (candidateProfileService == null || chatModel == null || !mayBeCandidateExistenceQuestion(question)) {
            return null;
        }
        return parseCandidateExistenceIntentWithLlm(question);
    }

    private CandidateExistenceIntent parseCandidateExistenceIntentWithLlm(String question) {
        String escapedQuestion = question == null
                ? "\"\""
                : "\"" + question.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        String prompt = CANDIDATE_EXISTENCE_INTENT_PROMPT.formatted(escapedQuestion);
        try {
            String raw = chatModel.chat(prompt);
            String response = stripCodeFence(raw);
            if (response.isBlank()) {
                return null;
            }
            Boolean existenceQuery = parseJsonBoolean(response, JSON_EXISTENCE_FLAG_PATTERN);
            String extractedName = parseJsonString(response, JSON_TARGET_NAME_PATTERN);
            if (Boolean.FALSE.equals(existenceQuery)) {
                return null;
            }
            if (extractedName == null || extractedName.isBlank()) {
                if (Boolean.TRUE.equals(existenceQuery) && looksLikeCandidateNameTarget(response)) {
                    extractedName = response;
                } else {
                    return null;
                }
            }
            String cleanedTarget = extractedName.replaceAll("^[`\"'\\s]+|[`\"'?.!,\\s]+$", "").trim();
            if (!looksLikeCandidateNameTarget(cleanedTarget)) {
                return null;
            }
            List<String> tokens = personNameTokens(normalizePersonName(cleanedTarget));
            return tokens.isEmpty() ? null : new CandidateExistenceIntent(cleanedTarget, tokens);
        } catch (RuntimeException ex) {
            log.debug("LLM existence intent fallback failed: {}", ex.getMessage());
            return null;
        }
    }

    private static String extractExistenceTarget(String question, Pattern pattern) {
        java.util.regex.Matcher matcher = pattern.matcher(question);
        if (!matcher.matches()) {
            return null;
        }
        String target = matcher.group(1);
        return target != null ? target.trim() : null;
    }

    private static boolean mayBeCandidateExistenceQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = normalize(question);
        if (!CANDIDATE_EXISTENCE_START_PATTERN.matcher(normalized).find()) {
            return false;
        }
        return matchesAnyPattern(normalized, CANDIDATE_EXISTENCE_REFERENCE_PATTERNS);
    }

    private static String stripCodeFence(String raw) {
        if (raw == null) {
            return "";
        }
        String trimmed = raw.trim();
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        String withoutStart = trimmed.replaceFirst("^```[a-zA-Z0-9_-]*\\s*", "");
        return withoutStart.replaceFirst("\\s*```\\s*$", "").trim();
    }

    private static String parseJsonString(String text, Pattern pattern) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String value = matcher.group(1);
        return value != null ? value.trim() : null;
    }

    private static Boolean parseJsonBoolean(String text, Pattern pattern) {
        if (text == null || text.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        String raw = matcher.group(1);
        if (raw == null) {
            return null;
        }
        if ("true".equalsIgnoreCase(raw.trim())) {
            return Boolean.TRUE;
        }
        if ("false".equalsIgnoreCase(raw.trim())) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static boolean looksLikeCandidateNameTarget(String target) {
        if (target == null || target.isBlank()) {
            return false;
        }
        if (target.contains("@") || target.matches(".*\\d.*")) {
            return false;
        }
        List<String> tokens = personNameTokens(normalizePersonName(target));
        if (tokens.size() < 2 || tokens.size() > 6) {
            return false;
        }
        return tokens.stream().noneMatch(CANDIDATE_EXISTENCE_NOISE_TERMS::contains);
    }

    private static String normalizePersonName(String value) {
        if (value == null) {
            return "";
        }
        String noMarks = Normalizer.normalize(value, Normalizer.Form.NFD).replaceAll("\\p{M}+", "");
        return noMarks.toLowerCase(Locale.ROOT).replaceAll("[^a-z\\s'-]+", " ").replaceAll("\\s+", " ").trim();
    }

    private static List<String> personNameTokens(String normalizedName) {
        if (normalizedName == null || normalizedName.isBlank()) {
            return List.of();
        }
        return java.util.Arrays.stream(normalizedName.split("[^a-z]+"))
                .map(String::trim)
                .filter(token -> token.length() >= 2)
                .distinct()
                .collect(Collectors.toList());
    }

    private static String safeText(String value) {
        return value == null || value.isBlank() ? "-" : value.trim();
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

    private record CandidateExistenceIntent(
            String targetName,
            List<String> queryTokens
    ) {
    }

    private record CandidateMatch(
            CandidateProfile candidate,
            double score
    ) {
    }

    private record CandidateFilterIntent(
            List<String> requiredSkills,
            List<String> requiredRoles,
            String locationFilter,
            Integer minYears,
            Integer maxYears,
            List<String> nameTokens,
            List<String> queryTerms
    ) {
        private boolean hasHardConstraints() {
            return locationFilter != null
                    || minYears != null
                    || maxYears != null
                    || (nameTokens != null && !nameTokens.isEmpty())
                    || (requiredRoles != null && !requiredRoles.isEmpty());
        }
    }

    private record CandidateFilterMatch(
            CandidateProfile candidate,
            double score,
            double keywordScore,
            List<String> matchedCriteria
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
