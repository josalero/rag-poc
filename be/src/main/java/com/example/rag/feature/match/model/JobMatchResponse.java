package com.example.rag.feature.match.model;

import java.util.List;

public record JobMatchResponse(
        List<JobMatchCandidate> items,
        int page,
        int pageSize,
        int total,
        List<String> inferredMustHaveSkills
) {
}
