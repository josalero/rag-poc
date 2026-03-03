package com.example.rag.match;

import java.util.List;

public record JobMatchResponse(
        List<JobMatchCandidate> items,
        int page,
        int pageSize,
        int total,
        List<String> inferredMustHaveSkills
) {
}
