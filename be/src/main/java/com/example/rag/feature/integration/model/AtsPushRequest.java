package com.example.rag.feature.integration.model;

import jakarta.validation.constraints.NotBlank;

public record AtsPushRequest(
        @NotBlank String candidateId,
        String jobId,
        String notes
) {
}
