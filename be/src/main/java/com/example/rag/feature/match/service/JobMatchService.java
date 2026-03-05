package com.example.rag.feature.match.service;

import com.example.rag.feature.match.model.*;

import com.example.rag.feature.candidate.model.CandidateProfile;
import com.example.rag.feature.candidate.service.CandidateProfileService;
import com.example.rag.feature.role.domain.TechnicalRoleCatalog;
import com.example.rag.feature.role.domain.TechnicalRoleCatalog.RoleDefinition;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class JobMatchService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final double DEFAULT_MATCH_SCORE = 0.75d;
    private static final int MAX_DESCRIPTION_TERMS = 12;
    private static final Pattern YEARS_PATTERN = Pattern.compile("(\\d{1,2})\\+?\\s+years", Pattern.CASE_INSENSITIVE);
    private static final Set<String> STOP_WORDS = Set.of(
            "the", "and", "for", "with", "from", "that", "this", "have", "has", "are", "was", "were",
            "into", "about", "which", "when", "where", "who", "what", "how", "why", "can", "you", "your",
            "our", "all", "any", "not", "use", "using", "more", "than", "over", "under", "does", "did",
            "been", "will", "would", "could", "should", "between", "after", "before", "through", "must",
            "need", "needs", "wanted", "role", "position", "candidate", "candidates", "engineer", "engineering",
            "experience", "years", "year", "build", "building", "develop", "developing", "developer",
            "senior", "junior", "intermediate", "principal", "lead", "team", "work", "working"
    );
    private static final List<RoleDefinition> ROLE_DEFINITIONS = TechnicalRoleCatalog.roleDefinitions();
    private static final Set<String> QA_ROLE_SIGNALS = TechnicalRoleCatalog.qaRoleSignals();
    private static final Set<String> QA_SKILL_SIGNALS = TechnicalRoleCatalog.qaSkillSignals();
    private static final Set<String> NON_QA_ENGINEERING_SIGNALS = TechnicalRoleCatalog.nonQaEngineeringSignals();
    private static final Set<String> QA_FALLBACK_MUST_HAVES = Set.of("QA", "TESTING");

    private final CandidateProfileService candidateProfileService;

    public JobMatchService(CandidateProfileService candidateProfileService) {
        this.candidateProfileService = candidateProfileService;
    }

    public JobMatchResponse match(JobMatchRequest request) {
        String description = request.jobDescription() == null ? "" : request.jobDescription().trim();
        RoleDefinition targetRole = inferTargetRole(description);
        RoleIntent roleIntent = inferRoleIntent(description);
        List<String> explicitMustHave = normalizeRequestedMustHave(request.mustHaveSkills());
        List<String> inferredMustHave = inferMustHaveSkills(description, List.of(), roleIntent, targetRole);
        List<String> appliedMustHave = explicitMustHave.isEmpty() ? inferredMustHave : explicitMustHave;
        List<String> descriptionTerms = extractDescriptionTerms(description, appliedMustHave, targetRole);
        Integer requiredYears = inferRequiredYears(description);
        String requestedSeniority = inferRequestedSeniority(description);
        double effectiveMinScore = request.minScore() != null
                ? clamp(request.minScore())
                : DEFAULT_MATCH_SCORE;

        List<JobMatchCandidate> ranked = candidateProfileService.allCandidates().stream()
                .map(candidate -> scoreCandidate(
                        candidate,
                        appliedMustHave,
                        requiredYears,
                        requestedSeniority,
                        roleIntent,
                        targetRole,
                        descriptionTerms))
                .filter(candidate -> candidate.overallScore() >= effectiveMinScore)
                .sorted(Comparator.comparingDouble(JobMatchCandidate::overallScore).reversed()
                        .thenComparing(JobMatchCandidate::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        int pageSize = request.pageSize() != null
                ? Math.min(Math.max(request.pageSize(), 1), MAX_PAGE_SIZE)
                : DEFAULT_PAGE_SIZE;
        int page = request.page() != null ? Math.max(1, request.page()) : 1;
        int total = ranked.size();
        int start = Math.min((page - 1) * pageSize, total);
        int end = Math.min(start + pageSize, total);
        List<JobMatchCandidate> items = start < end ? ranked.subList(start, end) : List.of();

        return new JobMatchResponse(items, page, pageSize, total, appliedMustHave);
    }

    private static JobMatchCandidate scoreCandidate(
            CandidateProfile candidate,
            List<String> mustHaveSkills,
            Integer requiredYears,
            String requestedSeniority,
            RoleIntent roleIntent,
            RoleDefinition targetRole,
            List<String> descriptionTerms) {
        Set<String> candidateSkills = listOrEmpty(candidate.skills()).stream()
                .map(TechnicalRoleCatalog::normalizeSkill)
                .filter(skill -> !skill.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        listOrEmpty(candidate.significantSkills()).stream()
                .map(TechnicalRoleCatalog::normalizeSkill)
                .filter(skill -> !skill.isBlank())
                .forEach(candidateSkills::add);
        String candidateCorpus = buildCandidateCorpus(candidate);

        List<String> matchedSkills = new ArrayList<>();
        List<String> missingMustHave = new ArrayList<>();
        for (String mustHave : mustHaveSkills) {
            if (hasSkillOrAlias(candidateSkills, candidateCorpus, mustHave)) {
                matchedSkills.add(mustHave);
            } else {
                missingMustHave.add(mustHave);
            }
        }

        double mustHaveCoverage = mustHaveSkills.isEmpty()
                ? 1.0d
                : ((double) matchedSkills.size()) / mustHaveSkills.size();
        double descriptionRelevance = scoreDescriptionRelevance(descriptionTerms, candidateCorpus);
        double skillCoverage = scoreRelevantSkillCoverage(candidateSkills, candidateCorpus, mustHaveSkills, targetRole);
        double yearsFit = scoreYearsFit(requiredYears, candidate.estimatedYearsExperience());
        double seniorityFit = scoreSeniorityFit(requestedSeniority, candidate.suggestedRoles());
        double roleFit = scoreRoleFit(roleIntent, targetRole, candidate, candidateCorpus);

        double overallScore;
        if (mustHaveSkills.isEmpty()) {
            overallScore = clamp(
                    (descriptionRelevance * 0.45d) +
                    (roleFit * 0.25d) +
                    (skillCoverage * 0.20d) +
                    (yearsFit * 0.07d) +
                    (seniorityFit * 0.03d)
            );
        } else {
            overallScore = clamp(
                    (mustHaveCoverage * 0.35d) +
                    (descriptionRelevance * 0.20d) +
                    (roleFit * 0.20d) +
                    (skillCoverage * 0.15d) +
                    (yearsFit * 0.07d) +
                    (seniorityFit * 0.03d)
            );
        }

        if (!mustHaveSkills.isEmpty() && mustHaveCoverage < 0.40d) {
            overallScore = clamp(overallScore * 0.80d);
        }

        if (roleIntent == RoleIntent.QA || targetRole != null) {
            if (roleFit < 0.30d) {
                overallScore = clamp(overallScore * 0.55d);
            } else if (roleFit < 0.50d) {
                overallScore = clamp(overallScore * 0.75d);
            }
        }

        return new JobMatchCandidate(
                candidate.id(),
                candidate.displayName(),
                overallScore,
                mustHaveCoverage,
                skillCoverage,
                yearsFit,
                seniorityFit,
                List.copyOf(matchedSkills),
                List.copyOf(missingMustHave),
                listOrEmpty(candidate.suggestedRoles()).stream().limit(2).toList()
        );
    }

    private static List<String> inferMustHaveSkills(
            String description,
            List<String> requestedMustHaveSkills,
            RoleIntent roleIntent,
            RoleDefinition targetRole) {
        Set<String> skills = new LinkedHashSet<>();
        if (requestedMustHaveSkills != null) {
            for (String skill : requestedMustHaveSkills) {
                if (skill == null || skill.isBlank()) {
                    continue;
                }
                skills.add(skill.trim().toUpperCase(Locale.ROOT));
            }
        }

        String canonicalizedDescription = TechnicalRoleCatalog.canonicalizeSkillText(description);
        for (String skill : TechnicalRoleCatalog.canonicalSkills()) {
            if (TechnicalRoleCatalog.containsSkillTerm(canonicalizedDescription, skill)) {
                skills.add(skill);
            }
        }

        if (targetRole != null && (requestedMustHaveSkills == null || requestedMustHaveSkills.isEmpty())) {
            targetRole.skillSignals().stream()
                    .limit(4)
                    .forEach(skills::add);
        }

        if (roleIntent == RoleIntent.QA) {
            boolean hasQaSignal = skills.stream()
                    .map(JobMatchService::normalize)
                    .anyMatch(signal -> QA_ROLE_SIGNALS.contains(signal) || QA_SKILL_SIGNALS.contains(signal));
            if (!hasQaSignal) {
                skills.addAll(QA_FALLBACK_MUST_HAVES);
            }
        }
        if (skills.size() < 3) {
            for (String fallback : extractFallbackMustHave(description)) {
                skills.add(fallback);
                if (skills.size() >= 6) {
                    break;
                }
            }
        }
        return List.copyOf(skills);
    }

    private static List<String> normalizeRequestedMustHave(List<String> requestedMustHaveSkills) {
        if (requestedMustHaveSkills == null || requestedMustHaveSkills.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String skill : requestedMustHaveSkills) {
            if (skill == null || skill.isBlank()) {
                continue;
            }
            String canonical = TechnicalRoleCatalog.normalizeSkill(skill);
            if (!canonical.isBlank()) {
                normalized.add(canonical);
            }
        }
        return List.copyOf(normalized);
    }

    private static List<String> extractFallbackMustHave(String description) {
        if (description == null || description.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> inferred = new LinkedHashSet<>();
        for (String token : canonicalize(description).split("\\s+")) {
            if (token == null || token.isBlank() || token.length() < 3) {
                continue;
            }
            if (STOP_WORDS.contains(token) || token.chars().allMatch(Character::isDigit)) {
                continue;
            }
            inferred.add(token.toUpperCase(Locale.ROOT));
            if (inferred.size() >= 6) {
                break;
            }
        }
        return List.copyOf(inferred);
    }

    private static List<String> extractDescriptionTerms(
            String description,
            List<String> mustHaveSkills,
            RoleDefinition targetRole) {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        if (description != null && !description.isBlank()) {
            String[] tokens = canonicalize(description).split("\\s+");
            for (String token : tokens) {
                if (token == null || token.isBlank() || token.length() < 3 || STOP_WORDS.contains(token)) {
                    continue;
                }
                terms.add(token);
                if (terms.size() >= MAX_DESCRIPTION_TERMS) {
                    break;
                }
            }
        }
        for (String mustHave : mustHaveSkills) {
            String normalized = canonicalize(mustHave);
            if (!normalized.isBlank()) {
                terms.add(normalized);
            }
            if (terms.size() >= MAX_DESCRIPTION_TERMS) {
                break;
            }
        }
        if (targetRole != null) {
            for (String signal : targetRole.keywordSignals()) {
                String normalized = canonicalize(signal);
                if (!normalized.isBlank()) {
                    terms.add(normalized);
                }
                if (terms.size() >= MAX_DESCRIPTION_TERMS) {
                    break;
                }
            }
        }
        return List.copyOf(terms);
    }

    private static Integer inferRequiredYears(String description) {
        Matcher matcher = YEARS_PATTERN.matcher(description == null ? "" : description);
        int max = 0;
        while (matcher.find()) {
            try {
                max = Math.max(max, Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // ignore malformed values
            }
        }
        return max > 0 ? max : null;
    }

    private static String inferRequestedSeniority(String description) {
        String lower = normalize(description);
        if (lower.contains("principal") || lower.contains("staff")) {
            return "principal";
        }
        if (lower.contains("senior") || lower.contains("lead")) {
            return "senior";
        }
        if (lower.contains("intermediate") || lower.contains("mid-level") || lower.contains("mid level")) {
            return "intermediate";
        }
        if (lower.contains("junior") || lower.contains("entry")) {
            return "junior";
        }
        return "";
    }

    private static RoleIntent inferRoleIntent(String description) {
        RoleDefinition inferredRole = inferTargetRole(description);
        if (inferredRole != null && TechnicalRoleCatalog.QA_ROLE_TITLE.equalsIgnoreCase(inferredRole.title())) {
            return RoleIntent.QA;
        }
        String normalized = canonicalize(description);
        if (matchesAnyTerm(normalized, QA_ROLE_SIGNALS)) {
            return RoleIntent.QA;
        }
        return RoleIntent.GENERAL;
    }

    private static RoleDefinition inferTargetRole(String description) {
        String normalized = canonicalize(description);
        if (normalized.isBlank()) {
            return null;
        }

        RoleDefinition bestRole = null;
        int bestScore = 0;
        for (RoleDefinition definition : ROLE_DEFINITIONS) {
            int score = 0;
            if (containsTerm(normalized, definition.title())) {
                score += 3;
            }
            for (String keyword : definition.keywordSignals()) {
                if (containsTerm(normalized, keyword)) {
                    score += 2;
                }
            }
            for (String skill : definition.skillSignals()) {
                if (containsTerm(normalized, skill)) {
                    score += 1;
                }
            }
            if (score > bestScore) {
                bestScore = score;
                bestRole = definition;
            }
        }
        return bestScore > 0 ? bestRole : null;
    }

    private static double scoreYearsFit(Integer requiredYears, Integer candidateYears) {
        if (requiredYears == null || requiredYears <= 0) {
            return 1.0d;
        }
        if (candidateYears == null || candidateYears <= 0) {
            return 0.0d;
        }
        if (candidateYears >= requiredYears) {
            return 1.0d;
        }
        return clamp((double) candidateYears / requiredYears);
    }

    private static double scoreSeniorityFit(String requestedSeniority, List<String> suggestedRoles) {
        if (requestedSeniority == null || requestedSeniority.isBlank()) {
            return 1.0d;
        }
        String requested = normalize(requestedSeniority);
        String roles = suggestedRoles == null ? "" : normalize(String.join(" ", suggestedRoles));
        if (roles.contains(requested)) {
            return 1.0d;
        }
        if ("senior".equals(requested) && roles.contains("principal")) {
            return 0.9d;
        }
        if ("intermediate".equals(requested) && roles.contains("senior")) {
            return 0.8d;
        }
        return 0.3d;
    }

    private static double scoreRelevantSkillCoverage(
            Set<String> candidateSkills,
            String candidateCorpus,
            List<String> mustHaveSkills,
            RoleDefinition targetRole) {
        if (targetRole != null && targetRole.skillSignals() != null && !targetRole.skillSignals().isEmpty()) {
            int hits = 0;
            int considered = 0;
            for (String signal : targetRole.skillSignals()) {
                if (signal == null || signal.isBlank()) {
                    continue;
                }
                considered += 1;
                if (hasSkillOrAlias(candidateSkills, candidateCorpus, signal)) {
                    hits += 1;
                }
                if (considered >= 8) {
                    break;
                }
            }
            if (considered > 0) {
                return clamp((double) hits / considered);
            }
        }

        if (mustHaveSkills != null && !mustHaveSkills.isEmpty()) {
            int hits = 0;
            for (String mustHave : mustHaveSkills) {
                if (hasSkillOrAlias(candidateSkills, candidateCorpus, mustHave)) {
                    hits += 1;
                }
            }
            return clamp((double) hits / mustHaveSkills.size());
        }

        return candidateSkills.isEmpty() ? 0.0d : Math.min(1.0d, candidateSkills.size() / 12.0d);
    }

    private static double scoreRoleFit(
            RoleIntent roleIntent,
            RoleDefinition targetRole,
            CandidateProfile candidate,
            String candidateCorpus) {
        if (targetRole == null && roleIntent != RoleIntent.QA) {
            return 1.0d;
        }

        String roleText = normalize(String.join(" ", listOrEmpty(candidate.suggestedRoles())));

        if (targetRole != null) {
            int hitScore = 0;
            if (containsTerm(roleText, targetRole.title())) {
                hitScore += 4;
            }
            for (String keyword : targetRole.keywordSignals()) {
                if (containsTerm(candidateCorpus, keyword)) {
                    hitScore += 2;
                }
            }
            for (String signal : targetRole.skillSignals()) {
                if (containsTerm(candidateCorpus, signal)) {
                    hitScore += 1;
                }
            }
            if (hitScore >= 9) {
                return 1.0d;
            }
            if (hitScore >= 6) {
                return 0.85d;
            }
            if (hitScore >= 3) {
                return 0.70d;
            }
            if (hitScore >= 1) {
                return 0.55d;
            }
            return 0.20d;
        }

        if (roleIntent != RoleIntent.QA) {
            return 0.7d;
        }

        int roleSignalHits = 0;
        for (String signal : QA_ROLE_SIGNALS) {
            if (containsTerm(candidateCorpus, signal)) {
                roleSignalHits += 1;
            }
        }

        int skillSignalHits = 0;
        for (String signal : QA_SKILL_SIGNALS) {
            if (containsTerm(candidateCorpus, signal)) {
                skillSignalHits += 1;
            }
        }

        if (matchesAnyTerm(roleText, Set.of("qa", "quality assurance", "sdet", "test engineer"))) {
            roleSignalHits += 2;
        }

        int totalHits = roleSignalHits + skillSignalHits;
        if (totalHits >= 6) {
            return 1.0d;
        }
        if (totalHits >= 4) {
            return 0.85d;
        }
        if (totalHits >= 2) {
            return 0.70d;
        }
        if (totalHits == 1) {
            return 0.55d;
        }

        if (matchesAnyTerm(candidateCorpus, NON_QA_ENGINEERING_SIGNALS)) {
            return 0.10d;
        }
        return 0.25d;
    }

    private static double scoreDescriptionRelevance(List<String> descriptionTerms, String candidateCorpus) {
        if (descriptionTerms == null || descriptionTerms.isEmpty()) {
            return 1.0d;
        }
        int hits = 0;
        for (String term : descriptionTerms) {
            if (containsTerm(candidateCorpus, term)) {
                hits += 1;
            }
        }
        if (hits == 0) {
            return 0.0d;
        }
        int denominator = Math.max(4, Math.min(descriptionTerms.size(), 6));
        return clamp((double) hits / denominator);
    }

    private static boolean hasSkillOrAlias(Set<String> candidateSkills, String candidateCorpus, String mustHave) {
        String canonicalMustHave = TechnicalRoleCatalog.normalizeSkill(mustHave);
        if (canonicalMustHave.isBlank()) {
            return false;
        }
        if (candidateSkills.contains(canonicalMustHave)) {
            return true;
        }
        for (String searchTerm : TechnicalRoleCatalog.skillSearchTerms(canonicalMustHave)) {
            if (containsTerm(candidateCorpus, searchTerm)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesAnyTerm(String text, Set<String> terms) {
        for (String term : terms) {
            if (containsTerm(text, term)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsTerm(String haystack, String needle) {
        String normalizedHaystack = canonicalize(haystack);
        String normalizedNeedle = canonicalize(needle);
        if (normalizedHaystack.isBlank() || normalizedNeedle.isBlank()) {
            return false;
        }
        return (" " + normalizedHaystack + " ").contains(" " + normalizedNeedle + " ");
    }

    private static String buildCandidateCorpus(CandidateProfile candidate) {
        List<String> parts = new ArrayList<>();
        parts.addAll(listOrEmpty(candidate.suggestedRoles()));
        parts.addAll(listOrEmpty(candidate.skills()));
        parts.addAll(listOrEmpty(candidate.significantSkills()));
        if (candidate.preview() != null) {
            parts.add(candidate.preview());
        }
        if (candidate.sourceFilename() != null) {
            parts.add(candidate.sourceFilename());
        }
        return canonicalize(String.join(" ", parts));
    }

    private static <T> List<T> listOrEmpty(List<T> values) {
        return values == null ? List.of() : values;
    }

    private static String canonicalize(String text) {
        String normalized = normalize(text);
        return normalized.replaceAll("[^a-z0-9+/#]+", " ").replaceAll("\\s+", " ").trim();
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
    }

    private static double clamp(double value) {
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private enum RoleIntent {
        GENERAL,
        QA
    }
}
