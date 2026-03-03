package com.example.rag.candidate;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record CandidateProfileVersion(
        String sourceFilename,
        Instant ingestedAt,
        List<String> skills,
        List<String> significantSkills,
        List<String> suggestedRoles,
        Integer estimatedYearsExperience,
        String location,
        String preview,
        String extractionMethod,
        String normalizedContentHash,
        int normalizedTextChars,
        Map<String, Double> fieldConfidence,
        Map<String, String> fieldEvidence,
        List<String> validationWarnings
) {
}
