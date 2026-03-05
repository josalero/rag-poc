package com.example.rag.feature.query.model;

import java.util.List;

public record QueryExplainability(
        List<String> matchedTerms,
        List<String> missingTerms,
        double confidenceScore
) {
}
