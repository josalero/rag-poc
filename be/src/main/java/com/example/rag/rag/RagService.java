package com.example.rag.rag;

import com.example.rag.candidate.CandidateProfileService;
import com.example.rag.feedback.QueryFeedbackService;
import com.example.rag.metrics.ObservabilityService;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class RagService {

    private static final int DEFAULT_MAX_RESULTS = 50;
    private static final int DEFAULT_MAX_ALLOWED_RESULTS = 200;
    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int DEFAULT_MAX_PAGE_SIZE = 100;
    private static final int MAX_CONTEXT_SEGMENTS = 20;
    private static final int MAX_EXPLAINABILITY_TERMS = 8;
    private static final double DEFAULT_MIN_SCORE = 0.75d;
    private static final double VECTOR_WEIGHT = 0.8d;
    private static final double KEYWORD_WEIGHT = 0.2d;
    private static final String DEFAULT_NO_RESULTS_ANSWER =
            "I couldn't find relevant information in the ingested resumes.";
    private static final String FAIRNESS_GUARDRAIL_MESSAGE = "I can't rank or filter candidates by protected attributes (such as age, gender, race, ethnicity, religion, disability, marital status, or nationality). Please ask using job-relevant skills, experience, and responsibilities.";
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
            "marital", "married", "pregnant", "pregnancy", "nationality", "national origin"
    );

    private static final Set<String> DISALLOWED_BIAS_INTENT_TERMS = Set.of(
            "rank", "filter", "prefer", "best", "hire", "select", "shortlist", "choose", "exclude"
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
                null,
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
                null,
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
                      QueryFeedbackService queryFeedbackService,
                      ObservabilityService observabilityService,
                      @Value("${app.rag.max-results:50}") int maxResults,
                      @Value("${app.rag.max-allowed-results:200}") int maxAllowedResults,
                      @Value("${app.rag.min-score:0.75}") double minScore,
                      @Value("${app.rag.no-results-answer:I couldn't find relevant information in the ingested resumes.}") String noResultsAnswer) {
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
            if (isSensitiveBiasRequest(normalizedQuestion)) {
                QueryResponse response = QueryResponse.of(
                        structuredPolicyAnswer(),
                        List.of(),
                        effectivePage,
                        effectivePageSize,
                        0,
                        new QueryExplainability(List.of(), List.of(), 0.0d));
                recordQuerySuccess(startedAt, 0);
                return response;
            }

            Embedding queryEmbedding = embeddingModel.embed(normalizedQuestion).content();
            EmbeddingSearchRequest request = EmbeddingSearchRequest.builder()
                    .queryEmbedding(queryEmbedding)
                    .maxResults(effectiveMaxResults)
                    .build();

            List<String> queryTerms = extractQueryTerms(normalizedQuestion);
            List<RankedMatch> rankedMatches = embeddingStore.search(request).matches().stream()
                    .map(match -> toRankedMatch(match, queryTerms))
                    .sorted((a, b) -> Double.compare(b.hybridScore(), a.hybridScore()))
                    .filter(match -> match.hybridScore() >= effectiveThreshold)
                    .toList();
            List<RankedMatch> uniqueMatches = deduplicateMatches(rankedMatches);

            if (uniqueMatches.isEmpty()) {
                QueryResponse response = QueryResponse.of(
                        noResultsAnswer,
                        List.of(),
                        effectivePage,
                        effectivePageSize,
                        0,
                        new QueryExplainability(List.of(), queryTerms, 0.0d));
                recordQuerySuccess(startedAt, 0);
                return response;
            }

            List<RankedMatch> contextMatches = uniqueMatches.stream()
                    .limit(MAX_CONTEXT_SEGMENTS)
                    .toList();
            String context = contextMatches.stream()
                    .map(match -> match.embeddingMatch().embedded().text())
                    .collect(java.util.stream.Collectors.joining("\n\n"));

            int totalSources = uniqueMatches.size();
            int startIndex = Math.min((effectivePage - 1) * effectivePageSize, totalSources);
            int endIndex = Math.min(startIndex + effectivePageSize, totalSources);

            List<QueryResponse.SourceSegment> sources = new ArrayList<>();
            for (int i = startIndex; i < endIndex; i++) {
                RankedMatch rankedMatch = uniqueMatches.get(i);
                String source = rankedMatch.source();
                String candidateId = source.isBlank() || candidateProfileService == null
                        ? ""
                        : candidateProfileService.getBySourceFilename(source)
                        .map(profile -> profile.id())
                        .orElse("");

                sources.add(new QueryResponse.SourceSegment(
                        rankedMatch.embeddingMatch().embedded().text(),
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

            QueryExplainability explainability = buildExplainability(queryTerms, uniqueMatches);
            String prompt = String.format(PROMPT_TEMPLATE, context, normalizedQuestion);
            String answer = chatModel.chat(prompt);
            QueryResponse response = QueryResponse.of(answer, sources, effectivePage, effectivePageSize, totalSources, explainability);
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

    private static RankedMatch toRankedMatch(EmbeddingMatch<TextSegment> embeddingMatch, List<String> queryTerms) {
        String text = embeddingMatch != null && embeddingMatch.embedded() != null && embeddingMatch.embedded().text() != null
                ? embeddingMatch.embedded().text()
                : "";
        TermMatch termMatch = scoreTermOverlap(text, queryTerms);
        double vectorScore = embeddingMatch != null ? embeddingMatch.score() : 0.0d;
        double hybridScore = clampScore(vectorScore * VECTOR_WEIGHT + termMatch.keywordScore() * KEYWORD_WEIGHT);
        return new RankedMatch(
                embeddingMatch,
                extractSource(embeddingMatch),
                vectorScore,
                termMatch.keywordScore(),
                hybridScore,
                termMatch.matchedTerms(),
                termMatch.missingTerms()
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
            if (!match.source().isBlank()) {
                key = "source:" + match.source().toLowerCase(Locale.ROOT);
            } else {
                String text = match.embeddingMatch() != null && match.embeddingMatch().embedded() != null
                        ? normalize(match.embeddingMatch().embedded().text())
                        : "";
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
        boolean hasSensitiveAttribute = SENSITIVE_ATTRIBUTE_TERMS.stream().anyMatch(lower::contains);
        boolean hasRankingIntent = DISALLOWED_BIAS_INTENT_TERMS.stream().anyMatch(lower::contains);
        return hasSensitiveAttribute && hasRankingIntent;
    }

    private static boolean isSensitiveTerm(String term) {
        if (term == null || term.isBlank()) {
            return false;
        }
        String normalized = normalize(term);
        return SENSITIVE_ATTRIBUTE_TERMS.stream().anyMatch(sensitive -> normalized.contains(normalize(sensitive)));
    }

    private static String structuredPolicyAnswer() {
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

    private static String extractSource(EmbeddingMatch<TextSegment> match) {
        if (match == null || match.embedded() == null || match.embedded().metadata() == null) {
            return "";
        }
        String source = match.embedded().metadata().getString("source");
        return source != null ? source.trim() : "";
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static double clampScore(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private record RankedMatch(
            EmbeddingMatch<TextSegment> embeddingMatch,
            String source,
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
}
