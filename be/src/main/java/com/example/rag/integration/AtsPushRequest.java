package com.example.rag.integration;

import jakarta.validation.constraints.NotBlank;

public record AtsPushRequest(
        @NotBlank String candidateId,
        String jobId,
        String notes
) {
}
