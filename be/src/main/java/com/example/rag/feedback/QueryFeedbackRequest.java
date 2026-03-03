package com.example.rag.feedback;

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
