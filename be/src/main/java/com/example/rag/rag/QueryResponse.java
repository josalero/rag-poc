package com.example.rag.rag;

import java.util.List;

public record QueryResponse(String answer, List<SourceSegment> sources) {

    public static QueryResponse of(String answer, List<SourceSegment> sources) {
        return new QueryResponse(answer, sources != null ? sources : List.of());
    }

    public record SourceSegment(String text, String source, double score) {}
}
