package com.example.rag.match;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;

import java.util.List;

public record JobMatchRequest(
        @NotBlank String jobDescription,
        List<String> mustHaveSkills,
        @DecimalMin("0.75") @DecimalMax("1.0") Double minScore,
        @Positive Integer page,
        @Positive Integer pageSize
) {
}
