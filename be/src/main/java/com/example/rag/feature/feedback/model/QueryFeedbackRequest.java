package com.example.rag.feature.feedback.model;

import jakarta.validation.constraints.NotBlank;

public record QueryFeedbackRequest(
        @NotBlank String question,
        @NotBlank String answer,
        boolean helpful,
        String notes,
        Double minScoreUsed,
        Double avgSourceScore
) {
}
