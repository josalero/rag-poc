package com.example.rag.rag;

import java.util.List;

public record QueryResponse(
        String answer,
        List<SourceSegment> sources,
        int page,
        int pageSize,
        int totalSources,
        QueryExplainability explainability
) {

    public static QueryResponse of(String answer, List<SourceSegment> sources) {
        List<SourceSegment> safeSources = sources != null ? sources : List.of();
        return new QueryResponse(
                answer,
                safeSources,
                1,
                safeSources.size(),
                safeSources.size(),
                new QueryExplainability(List.of(), List.of(), 0.0d));
    }

    public static QueryResponse of(String answer, List<SourceSegment> sources, int page, int pageSize, int totalSources) {
        return new QueryResponse(
                answer,
                sources != null ? sources : List.of(),
                page,
                pageSize,
                totalSources,
                new QueryExplainability(List.of(), List.of(), 0.0d));
    }

    public static QueryResponse of(
            String answer,
            List<SourceSegment> sources,
            int page,
            int pageSize,
            int totalSources,
            QueryExplainability explainability) {
        return new QueryResponse(
                answer,
                sources != null ? sources : List.of(),
                page,
                pageSize,
                totalSources,
                explainability != null ? explainability : new QueryExplainability(List.of(), List.of(), 0.0d));
    }

    public record SourceSegment(
            String text,
            String source,
            double score,
            int rank,
            String candidateId,
            double vectorScore,
            double keywordScore,
            List<String> matchedTerms,
            List<String> missingTerms
    ) {}
}
