package com.example.rag.eval;

import com.example.rag.rag.QueryResponse;
import com.example.rag.rag.RagService;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Service
public class RagEvalService {

    private static final List<EvalCaseRequest> DEFAULT_EVAL_SUITE = List.of(
            new EvalCaseRequest(
                    "Which candidates mention Java and Spring?",
                    List.of("java", "spring"),
                    List.of()
            ),
            new EvalCaseRequest(
                    "Which profiles mention AWS, Docker, or Kubernetes?",
                    List.of("aws", "docker", "kubernetes"),
                    List.of()
            ),
            new EvalCaseRequest(
                    "Who appears aligned for backend engineering roles?",
                    List.of("backend", "engineer"),
                    List.of()
            )
    );

    private final RagService ragService;

    public RagEvalService(RagService ragService) {
        this.ragService = ragService;
    }

    public EvalRunResponse run(EvalRunRequest request) {
        List<EvalCaseRequest> casesToRun = request != null && request.queries() != null && !request.queries().isEmpty()
                ? request.queries()
                : DEFAULT_EVAL_SUITE;
        Integer maxResults = request != null ? request.maxResults() : null;
        Double minScore = request != null ? request.minScore() : null;
        Boolean useFeedbackTuning = request != null ? request.useFeedbackTuning() : Boolean.FALSE;

        List<EvalCaseResult> caseResults = new ArrayList<>();
        for (EvalCaseRequest evalCase : casesToRun) {
            if (evalCase == null || evalCase.question() == null || evalCase.question().isBlank()) {
                continue;
            }
            QueryResponse queryResponse = ragService.query(
                    evalCase.question().trim(),
                    maxResults,
                    minScore,
                    1,
                    10,
                    useFeedbackTuning
            );

            double termRecall = recall(
                    evalCase.expectedTerms(),
                    queryResponse.explainability() != null ? queryResponse.explainability().matchedTerms() : List.of()
            );
            List<String> returnedSources = queryResponse.sources().stream()
                    .map(QueryResponse.SourceSegment::source)
                    .filter(source -> source != null && !source.isBlank())
                    .toList();
            double sourceRecall = recall(evalCase.expectedSources(), returnedSources);

            caseResults.add(new EvalCaseResult(
                    evalCase.question(),
                    termRecall,
                    sourceRecall,
                    queryResponse.explainability() != null ? queryResponse.explainability().confidenceScore() : 0.0d,
                    queryResponse.totalSources(),
                    queryResponse.explainability() != null ? queryResponse.explainability().matchedTerms() : List.of(),
                    queryResponse.explainability() != null ? queryResponse.explainability().missingTerms() : List.of()
            ));
        }

        double avgTermRecall = caseResults.stream().mapToDouble(EvalCaseResult::termRecall).average().orElse(0.0d);
        double avgSourceRecall = caseResults.stream().mapToDouble(EvalCaseResult::sourceRecall).average().orElse(0.0d);
        double avgConfidence = caseResults.stream().mapToDouble(EvalCaseResult::confidenceScore).average().orElse(0.0d);

        return new EvalRunResponse(
                Instant.now(),
                caseResults.size(),
                avgTermRecall,
                avgSourceRecall,
                avgConfidence,
                List.copyOf(caseResults)
        );
    }

    private static double recall(List<String> expected, List<String> actual) {
        if (expected == null || expected.isEmpty()) {
            return 1.0d;
        }
        Set<String> expectedSet = normalizeSet(expected);
        if (expectedSet.isEmpty()) {
            return 1.0d;
        }
        Set<String> actualSet = normalizeSet(actual);
        long matched = expectedSet.stream().filter(actualSet::contains).count();
        return (double) matched / expectedSet.size();
    }

    private static Set<String> normalizeSet(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Set.of();
        }
        Set<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            if (value == null || value.isBlank()) {
                continue;
            }
            normalized.add(value.toLowerCase(Locale.ROOT).trim());
        }
        return normalized;
    }
}
