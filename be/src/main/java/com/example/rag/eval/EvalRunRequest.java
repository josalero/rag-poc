package com.example.rag.eval;

import java.util.List;

public record EvalRunRequest(
        List<EvalCaseRequest> queries,
        Integer maxResults,
        Double minScore,
        Boolean useFeedbackTuning
) {
}
