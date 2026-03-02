package com.example.rag.rag;

import jakarta.validation.constraints.NotBlank;

public record QueryRequest(@NotBlank String question) {}
