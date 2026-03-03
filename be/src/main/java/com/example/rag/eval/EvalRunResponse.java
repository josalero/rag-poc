package com.example.rag.eval;

import java.time.Instant;
import java.util.List;

public record EvalRunResponse(
        Instant ranAt,
        int totalQueries,
        double averageTermRecall,
        double averageSourceRecall,
        double averageConfidence,
        List<EvalCaseResult> cases
) {
}
