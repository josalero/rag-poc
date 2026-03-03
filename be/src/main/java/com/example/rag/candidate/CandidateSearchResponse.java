package com.example.rag.candidate;

import java.util.List;

public record CandidateSearchResponse(
        List<CandidateProfile> items,
        int page,
        int pageSize,
        int total
) {
}
