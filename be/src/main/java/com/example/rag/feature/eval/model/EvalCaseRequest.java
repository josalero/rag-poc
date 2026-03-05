package com.example.rag.feature.eval.model;

import java.util.List;

public record EvalCaseRequest(
        String question,
        List<String> expectedTerms,
        List<String> expectedSources
) {
}
