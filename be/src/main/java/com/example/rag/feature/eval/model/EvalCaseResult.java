package com.example.rag.feature.eval.model;

import java.util.List;

public record EvalCaseResult(
        String question,
        double termRecall,
        double sourceRecall,
        double confidenceScore,
        int returnedSources,
        List<String> matchedTerms,
        List<String> missingTerms
) {
}
