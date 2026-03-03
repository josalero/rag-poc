package com.example.rag.feedback;

import java.time.Instant;

public record QueryFeedbackEntry(
        String question,
        String answer,
        boolean helpful,
        String notes,
        Instant createdAt
) {
}
