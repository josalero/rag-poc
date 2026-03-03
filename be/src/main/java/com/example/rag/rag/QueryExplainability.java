package com.example.rag.rag;

import java.util.List;

public record QueryExplainability(
        List<String> matchedTerms,
        List<String> missingTerms,
        double confidenceScore
) {
}
