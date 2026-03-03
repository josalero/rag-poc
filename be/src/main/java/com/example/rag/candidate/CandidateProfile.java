package com.example.rag.candidate;

import java.time.Instant;
import java.util.List;

public record CandidateProfile(
        String id,
        String sourceFilename,
        List<String> sourceFilenames,
        String displayName,
        String email,
        String phone,
        String linkedinUrl,
        String githubUrl,
        String portfolioUrl,
        List<String> skills,
        List<String> significantSkills,
        List<String> suggestedRoles,
        Integer estimatedYearsExperience,
        String location,
        long fileSizeBytes,
        Instant fileLastModifiedAt,
        Instant lastIngestedAt,
        String preview
) {
}
