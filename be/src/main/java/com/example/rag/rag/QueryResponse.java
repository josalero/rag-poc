package com.example.rag.rag;

import java.util.List;

public record QueryResponse(
        String answer,
        List<SourceSegment> sources,
        int page,
        int pageSize,
        int totalSources
) {

    public static QueryResponse of(String answer, List<SourceSegment> sources) {
        List<SourceSegment> safeSources = sources != null ? sources : List.of();
        return new QueryResponse(answer, safeSources, 1, safeSources.size(), safeSources.size());
    }

    public static QueryResponse of(String answer, List<SourceSegment> sources, int page, int pageSize, int totalSources) {
        return new QueryResponse(answer, sources != null ? sources : List.of(), page, pageSize, totalSources);
    }

    public record SourceSegment(String text, String source, double score, int rank, String candidateId) {}
}
