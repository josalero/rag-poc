package com.example.rag.match;

import com.example.rag.candidate.CandidateProfile;
import com.example.rag.candidate.CandidateProfileService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class JobMatchService {

    private static final int DEFAULT_PAGE_SIZE = 10;
    private static final int MAX_PAGE_SIZE = 100;
    private static final double MIN_MATCH_SCORE = 0.75d;
    private static final Pattern YEARS_PATTERN = Pattern.compile("(\\d{1,2})\\+?\\s+years", Pattern.CASE_INSENSITIVE);
    private static final Set<String> QA_ROLE_SIGNALS = Set.of(
            "qa", "quality assurance", "sdet", "software tester", "test engineer", "tester",
            "test automation", "automation testing", "manual testing", "regression testing",
            "api testing", "quality analyst"
    );
    private static final Set<String> QA_SKILL_SIGNALS = Set.of(
            "selenium", "cypress", "playwright", "postman", "testng", "junit",
            "cucumber", "bdd", "tdd", "xray", "jira", "appium", "rest assured"
    );
    private static final Set<String> NON_QA_ENGINEERING_SIGNALS = Set.of(
            "backend engineer", "full-stack engineer", "frontend engineer", "software engineer",
            "devops", "platform engineer", "microservice", "react", "spring", "node"
    );
    private static final Set<String> QA_FALLBACK_MUST_HAVES = Set.of("QA", "TESTING");
    private static final Map<String, List<String>> MUST_HAVE_ALIASES = Map.of(
            "qa", List.of("qa", "quality assurance", "sdet", "software tester", "test engineer", "tester"),
            "testing", List.of("testing", "test engineer", "manual testing", "quality assurance"),
            "test automation", List.of("test automation", "automation testing", "selenium", "cypress", "playwright", "sdet"),
            "api testing", List.of("api testing", "postman", "rest assured"),
            "ci/cd", List.of("ci/cd", "ci cd", "jenkins", "github actions", "gitlab ci")
    );

    private final CandidateProfileService candidateProfileService;

    public JobMatchService(CandidateProfileService candidateProfileService) {
        this.candidateProfileService = candidateProfileService;
    }

    public JobMatchResponse match(JobMatchRequest request) {
        String description = request.jobDescription() == null ? "" : request.jobDescription().trim();
        RoleIntent roleIntent = inferRoleIntent(description);
        List<String> inferredMustHave = inferMustHaveSkills(description, request.mustHaveSkills(), roleIntent);
        Integer requiredYears = inferRequiredYears(description);
        String requestedSeniority = inferRequestedSeniority(description);
        double effectiveMinScore = request.minScore() != null
                ? Math.max(MIN_MATCH_SCORE, clamp(request.minScore()))
                : MIN_MATCH_SCORE;

        List<JobMatchCandidate> ranked = candidateProfileService.allCandidates().stream()
                .map(candidate -> scoreCandidate(candidate, inferredMustHave, requiredYears, requestedSeniority, roleIntent))
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

        return new JobMatchResponse(items, page, pageSize, total, inferredMustHave);
    }

    private static JobMatchCandidate scoreCandidate(
            CandidateProfile candidate,
            List<String> mustHaveSkills,
            Integer requiredYears,
            String requestedSeniority,
            RoleIntent roleIntent) {
        Set<String> candidateSkills = listOrEmpty(candidate.skills()).stream()
                .map(JobMatchService::normalize)
                .filter(skill -> !skill.isBlank())
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        listOrEmpty(candidate.significantSkills()).stream()
                .map(JobMatchService::normalize)
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
        double skillCoverage = scoreRelevantSkillCoverage(candidateSkills, candidateCorpus, roleIntent);
        double yearsFit = scoreYearsFit(requiredYears, candidate.estimatedYearsExperience());
        double seniorityFit = scoreSeniorityFit(requestedSeniority, candidate.suggestedRoles());
        double roleFit = scoreRoleFit(roleIntent, candidate, candidateCorpus);

        double overallScore = roleIntent == RoleIntent.QA
                ? clamp(
                (mustHaveCoverage * 0.35d) +
                (roleFit * 0.35d) +
                (yearsFit * 0.15d) +
                (seniorityFit * 0.10d) +
                (skillCoverage * 0.05d)
        )
                : clamp(
                (mustHaveCoverage * 0.45d) +
                (skillCoverage * 0.25d) +
                (yearsFit * 0.20d) +
                (seniorityFit * 0.10d)
        );

        if (roleIntent == RoleIntent.QA) {
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
            RoleIntent roleIntent) {
        Set<String> skills = new LinkedHashSet<>();
        if (requestedMustHaveSkills != null) {
            for (String skill : requestedMustHaveSkills) {
                if (skill == null || skill.isBlank()) {
                    continue;
                }
                skills.add(skill.trim().toUpperCase(Locale.ROOT));
            }
        }

        String lower = normalize(description);
        List<String> known = List.of(
                "JAVA", "SPRING", "KOTLIN", "PYTHON", "JAVASCRIPT", "TYPESCRIPT", "REACT", "NODE",
                "POSTGRESQL", "MYSQL", "AWS", "AZURE", "GCP", "DOCKER", "KUBERNETES", "TERRAFORM",
                "CI/CD", "REST", "GRAPHQL", "RAG", "LLM", "LANGCHAIN",
                "QA", "SDET", "TESTING", "TEST AUTOMATION", "API TESTING", "SELENIUM", "CYPRESS",
                "PLAYWRIGHT", "POSTMAN", "CUCUMBER", "TESTNG", "JUNIT"
        );
        for (String skill : known) {
            if (lower.contains(skill.toLowerCase(Locale.ROOT))) {
                skills.add(skill);
            }
        }

        if (roleIntent == RoleIntent.QA) {
            boolean hasQaSignal = skills.stream()
                    .map(JobMatchService::normalize)
                    .anyMatch(signal -> QA_ROLE_SIGNALS.contains(signal) || QA_SKILL_SIGNALS.contains(signal));
            if (!hasQaSignal) {
                skills.addAll(QA_FALLBACK_MUST_HAVES);
            }
        }
        return List.copyOf(skills);
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
        String normalized = normalize(description);
        if (matchesAnyTerm(normalized, QA_ROLE_SIGNALS)) {
            return RoleIntent.QA;
        }
        return RoleIntent.GENERAL;
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
            RoleIntent roleIntent) {
        if (roleIntent != RoleIntent.QA) {
            return candidateSkills.isEmpty() ? 0.0d : Math.min(1.0d, candidateSkills.size() / 10.0d);
        }
        int hits = 0;
        for (String signal : QA_SKILL_SIGNALS) {
            if (containsTerm(candidateCorpus, signal)) {
                hits += 1;
            }
        }
        if (hits >= 5) {
            return 1.0d;
        }
        return clamp(hits / 5.0d);
    }

    private static double scoreRoleFit(RoleIntent roleIntent, CandidateProfile candidate, String candidateCorpus) {
        if (roleIntent != RoleIntent.QA) {
            return 1.0d;
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

        if (matchesAnyTerm(normalize(String.join(" ", listOrEmpty(candidate.suggestedRoles()))), Set.of("qa", "quality assurance", "sdet", "test engineer"))) {
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

    private static boolean hasSkillOrAlias(Set<String> candidateSkills, String candidateCorpus, String mustHave) {
        String normalizedMustHave = normalize(mustHave);
        if (normalizedMustHave.isBlank()) {
            return false;
        }
        if (candidateSkills.contains(normalizedMustHave) || containsTerm(candidateCorpus, normalizedMustHave)) {
            return true;
        }
        List<String> aliases = MUST_HAVE_ALIASES.get(normalizedMustHave);
        if (aliases == null || aliases.isEmpty()) {
            return false;
        }
        for (String alias : aliases) {
            if (containsTerm(candidateCorpus, alias)) {
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
