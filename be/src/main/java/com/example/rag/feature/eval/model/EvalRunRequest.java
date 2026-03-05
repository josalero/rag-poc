package com.example.rag.feature.eval.model;

import java.util.List;

public record EvalRunRequest(
        List<EvalCaseRequest> queries,
        Integer maxResults,
        Double minScore,
        Boolean useFeedbackTuning
) {
}
