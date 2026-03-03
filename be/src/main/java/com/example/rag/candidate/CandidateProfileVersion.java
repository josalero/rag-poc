package com.example.rag.candidate;

import java.time.Instant;
import java.util.List;

public record CandidateProfileVersion(
        String sourceFilename,
        Instant ingestedAt,
        List<String> skills,
        List<String> significantSkills,
        List<String> suggestedRoles,
        Integer estimatedYearsExperience,
        String location,
        String preview
) {
}
