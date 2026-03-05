package com.example.rag.feature.candidate.model;

import java.util.List;

public record CandidateSearchResponse(
        List<CandidateProfile> items,
        int page,
        int pageSize,
        int total
) {
}
