package com.example.rag.match;

import java.util.List;

public record JobMatchCandidate(
        String candidateId,
        String displayName,
        double overallScore,
        double mustHaveCoverage,
        double skillCoverage,
        double yearsFit,
        double seniorityFit,
        List<String> matchedSkills,
        List<String> missingMustHave,
        List<String> suggestedRoles
) {
}
