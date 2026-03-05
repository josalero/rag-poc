package com.example.rag.feature.feedback.model;

import java.time.Instant;

public record QueryFeedbackEntry(
        String question,
        String answer,
        boolean helpful,
        String notes,
        Double minScoreUsed,
        Double avgSourceScore,
        Instant createdAt
) {
}
