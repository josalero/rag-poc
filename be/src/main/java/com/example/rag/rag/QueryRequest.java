package com.example.rag.rag;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;

public record QueryRequest(
        @NotBlank String question,
        @Positive Integer maxResults,
        @DecimalMin("0.0") Double minScore,
        @Positive Integer page,
        @Positive Integer pageSize
) {}
