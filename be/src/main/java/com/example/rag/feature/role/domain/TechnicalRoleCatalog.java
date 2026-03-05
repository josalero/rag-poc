package com.example.rag.feature.role.domain;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public final class TechnicalRoleCatalog {

    public static final String QA_ROLE_TITLE = "QA / Test Engineer";
    public static final String TECH_LEAD_ROLE_TITLE = "Tech Lead";
    public static final String ENGINEERING_MANAGER_ROLE_TITLE = "Engineering Manager";

    public static final Set<String> QA_TRACK_KEYWORDS = Set.of(
            "qa", "quality assurance", "sdet", "test engineer", "software tester",
            "test automation", "automation testing", "manual testing", "regression testing",
            "api testing", "test cases", "quality analyst", "tester"
    );

    public static final Set<String> TECH_LEAD_TRACK_KEYWORDS = Set.of(
            "tech lead", "team lead", "lead engineer", "lead developer", "led team",
            "architecture", "architected", "mentored", "technical leadership"
    );

    public static final Set<String> MANAGER_TRACK_KEYWORDS = Set.of(
            "engineering manager", "people manager", "line manager", "manager of",
            "managed team", "hiring", "performance review", "career development",
            "director", "head of engineering"
    );

    private static final Set<String> QA_SKILL_SIGNALS = Set.of(
            "selenium", "cypress", "playwright", "postman", "testng", "junit",
            "cucumber", "bdd", "tdd", "xray", "jira", "appium", "rest assured"
    );

    private static final Set<String> NON_QA_ENGINEERING_SIGNALS = Set.of(
            "backend engineer", "full-stack engineer", "frontend engineer", "software engineer",
            "devops", "platform engineer", "microservice", "react", "spring", "node"
    );

    private static final Set<String> SENIORITY_PREFIXES = Set.of(
            "junior", "intermediate", "mid", "mid-level", "senior", "principal", "staff"
    );

    private static final List<String> CANONICAL_SKILLS = List.of(
            "JAVA", "SPRING", "SPRING BOOT", "KOTLIN", "JPA/HIBERNATE", "MAVEN", "GRADLE",
            "PYTHON", "JAVASCRIPT", "TYPESCRIPT", "REACT", "NODE", "ANGULAR", "VUE",
            "HTML", "CSS", "TAILWIND",
            "POSTGRESQL", "MYSQL", "SQL", "NOSQL", "REDIS",
            "KAFKA", "RABBITMQ", "MICROSERVICES",
            "AWS", "AZURE", "GCP", "DOCKER", "KUBERNETES", "TERRAFORM", "HELM", "ARGOCD", "ANSIBLE",
            "CI/CD", "JENKINS", "GITHUB ACTIONS", "GITLAB CI", "GIT", "LINUX", "BASH",
            "PROMETHEUS", "GRAFANA", "SRE",
            "REST", "GRAPHQL",
            "RAG", "LLM", "LANGCHAIN", "LANGCHAIN4J", "OPENAI", "EMBEDDINGS", "PGVECTOR",
            "QA", "SDET", "TESTING", "TEST AUTOMATION", "API TESTING", "SELENIUM", "CYPRESS",
            "PLAYWRIGHT", "POSTMAN", "CUCUMBER", "TESTNG", "JUNIT", "APPIUM", "JMETER", "KARATE"
    );

    private static final Map<String, List<String>> SKILL_ALIASES = Map.ofEntries(
            Map.entry("SPRING", List.of("spring framework")),
            Map.entry("SPRING BOOT", List.of("springboot", "spring-boot")),
            Map.entry("JPA/HIBERNATE", List.of("jpa", "hibernate")),
            Map.entry("JAVASCRIPT", List.of("js", "ecmascript")),
            Map.entry("TYPESCRIPT", List.of("ts")),
            Map.entry("NODE", List.of("nodejs", "node js")),
            Map.entry("POSTGRESQL", List.of("postgres", "psql")),
            Map.entry("NOSQL", List.of("no sql")),
            Map.entry("RABBITMQ", List.of("rabbit mq")),
            Map.entry("MICROSERVICES", List.of("microservices", "microservice")),
            Map.entry("KUBERNETES", List.of("k8s", "kube")),
            Map.entry("TERRAFORM", List.of("tf")),
            Map.entry("CI/CD", List.of("cicd", "ci cd", "continuous integration", "continuous delivery")),
            Map.entry("GITHUB ACTIONS", List.of("github action", "gh actions", "gha")),
            Map.entry("GITLAB CI", List.of("gitlab-ci")),
            Map.entry("LANGCHAIN4J", List.of("langchain 4j", "langchain-java")),
            Map.entry("OPENAI", List.of("open ai", "chatgpt api", "gpt api")),
            Map.entry("EMBEDDINGS", List.of("embedding", "vector embeddings", "vector embedding")),
            Map.entry("PGVECTOR", List.of("pg vector", "postgres vector", "postgresql vector")),
            Map.entry("TEST AUTOMATION", List.of("automation testing", "qa automation")),
            Map.entry("API TESTING", List.of("api tests", "api test", "rest assured")),
            Map.entry("JMETER", List.of("j meter"))
    );

    private static final Map<String, String> ALIAS_TO_CANONICAL = buildAliasToCanonical();
    private static final Map<String, Set<String>> CANONICAL_SEARCH_TERMS = buildCanonicalSearchTerms();

    private static final List<RoleDefinition> ROLE_DEFINITIONS = List.of(
            new RoleDefinition(
                    "Backend Engineer",
                    List.of("JAVA", "SPRING", "SPRING BOOT", "KOTLIN", "REST", "POSTGRESQL", "MYSQL", "SQL", "MICROSERVICES"),
                    List.of("backend", "microservice", "api"),
                    3,
                    null
            ),
            new RoleDefinition(
                    "Full-Stack Engineer",
                    List.of("JAVASCRIPT", "TYPESCRIPT", "REACT", "NODE", "JAVA", "SPRING", "SPRING BOOT"),
                    List.of("full stack", "frontend and backend"),
                    4,
                    null
            ),
            new RoleDefinition(
                    "Frontend Engineer",
                    List.of("REACT", "JAVASCRIPT", "TYPESCRIPT", "GRAPHQL", "HTML", "CSS"),
                    List.of("frontend", "ui", "web app"),
                    3,
                    null
            ),
            new RoleDefinition(
                    "DevOps / Platform Engineer",
                    List.of("DOCKER", "KUBERNETES", "TERRAFORM", "AWS", "AZURE", "GCP", "CI/CD", "JENKINS", "HELM", "ARGOCD", "ANSIBLE", "PROMETHEUS", "GRAFANA", "SRE"),
                    List.of("devops", "platform", "infrastructure", "sre", "observability", "site reliability"),
                    4,
                    null
            ),
            new RoleDefinition(
                    QA_ROLE_TITLE,
                    List.of("QA", "SDET", "TESTING", "TEST AUTOMATION", "API TESTING", "SELENIUM", "CYPRESS", "PLAYWRIGHT", "POSTMAN", "CUCUMBER", "TESTNG", "JUNIT", "APPIUM", "JMETER", "KARATE"),
                    List.of("qa", "quality assurance", "sdet", "test engineer", "software tester", "test automation", "automation testing", "manual testing", "regression testing", "api testing"),
                    3,
                    null
            ),
            new RoleDefinition(
                    "Cloud Engineer",
                    List.of("AWS", "AZURE", "GCP", "TERRAFORM", "KUBERNETES"),
                    List.of("cloud", "aws", "azure", "gcp"),
                    3,
                    null
            ),
            new RoleDefinition(
                    "AI Engineer (LLM/RAG)",
                    List.of("PYTHON", "LLM", "RAG", "LANGCHAIN", "LANGCHAIN4J", "OPENAI", "EMBEDDINGS", "PGVECTOR"),
                    List.of("llm", "rag", "prompt", "retrieval"),
                    3,
                    null
            ),
            new RoleDefinition(
                    TECH_LEAD_ROLE_TITLE,
                    List.of("JAVA", "SPRING", "AWS", "KUBERNETES", "CI/CD"),
                    List.of("tech lead", "team lead", "lead engineer", "lead developer", "architect", "architecture", "mentored", "led team"),
                    3,
                    6
            ),
            new RoleDefinition(
                    ENGINEERING_MANAGER_ROLE_TITLE,
                    List.of("JAVA", "SPRING", "AWS", "KUBERNETES", "CI/CD"),
                    List.of("engineering manager", "people manager", "line manager", "managed team", "hiring", "performance review", "director"),
                    3,
                    8
            )
    );

    private TechnicalRoleCatalog() {
    }

    public static List<RoleDefinition> roleDefinitions() {
        return ROLE_DEFINITIONS;
    }

    public static Set<String> qaRoleSignals() {
        return QA_TRACK_KEYWORDS;
    }

    public static Set<String> qaSkillSignals() {
        return QA_SKILL_SIGNALS;
    }

    public static Set<String> nonQaEngineeringSignals() {
        return NON_QA_ENGINEERING_SIGNALS;
    }

    public static List<String> canonicalSkills() {
        return CANONICAL_SKILLS;
    }

    public static String normalizeSkill(String rawSkill) {
        if (rawSkill == null || rawSkill.isBlank()) {
            return "";
        }
        String normalizedToken = normalizeSkillToken(rawSkill);
        if (normalizedToken.isBlank()) {
            return "";
        }
        String canonical = ALIAS_TO_CANONICAL.get(normalizedToken);
        if (canonical != null) {
            return canonical;
        }
        return rawSkill.replaceAll("\\s+", " ").trim().toUpperCase(Locale.ROOT);
    }

    public static Set<String> skillSearchTerms(String skillOrAlias) {
        String canonical = normalizeSkill(skillOrAlias);
        if (canonical.isBlank()) {
            return Set.of();
        }
        Set<String> terms = CANONICAL_SEARCH_TERMS.get(canonical);
        if (terms != null && !terms.isEmpty()) {
            return terms;
        }
        String normalized = normalizeSkillToken(skillOrAlias);
        return normalized.isBlank() ? Set.of() : Set.of(normalized);
    }

    public static String canonicalizeSkillText(String value) {
        return normalizeSkillToken(value);
    }

    public static boolean containsSkillTerm(String canonicalizedText, String skillOrAlias) {
        String haystack = canonicalizeSkillText(canonicalizedText);
        if (haystack.isBlank()) {
            return false;
        }
        for (String term : skillSearchTerms(skillOrAlias)) {
            if (containsNormalizedTerm(haystack, term)) {
                return true;
            }
        }
        return false;
    }

    public static int countSkillOccurrences(String canonicalizedText, String skillOrAlias) {
        String haystack = canonicalizeSkillText(canonicalizedText);
        if (haystack.isBlank()) {
            return 0;
        }
        int total = 0;
        for (String term : skillSearchTerms(skillOrAlias)) {
            total += countNormalizedTermOccurrences(haystack, term);
        }
        return total;
    }

    public static String llmRoleCatalogPrompt() {
        return ROLE_DEFINITIONS.stream()
                .map(def -> "- " + def.title() + ": core skills=" + String.join(", ", def.skillSignals().stream().limit(6).toList()))
                .collect(Collectors.joining("\n"));
    }

    public static String normalizeRoleTitle(String rawTitle) {
        if (rawTitle == null || rawTitle.isBlank()) {
            return "";
        }
        String compact = rawTitle.replaceAll("\\s+", " ").trim();
        if (compact.isBlank()) {
            return "";
        }

        String[] tokens = compact.split("\\s+");
        String maybePrefix = tokens.length > 1 ? tokens[0].toLowerCase(Locale.ROOT) : "";
        String prefix = SENIORITY_PREFIXES.contains(maybePrefix)
                ? Character.toUpperCase(maybePrefix.charAt(0)) + maybePrefix.substring(1) + " "
                : "";
        String base = prefix.isBlank() ? compact : compact.substring(tokens[0].length()).trim();

        for (RoleDefinition definition : ROLE_DEFINITIONS) {
            if (definition.title().equalsIgnoreCase(base)) {
                return prefix + definition.title();
            }
        }

        String normalizedBase = normalize(base);
        for (RoleDefinition definition : ROLE_DEFINITIONS) {
            if (normalizedBase.contains(normalize(definition.title()))) {
                return prefix + definition.title();
            }
        }
        return compact;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9+/#]+", " ").replaceAll("\\s+", " ").trim();
    }

    private static Map<String, String> buildAliasToCanonical() {
        Map<String, String> map = new LinkedHashMap<>();
        for (String canonical : CANONICAL_SKILLS) {
            map.put(normalizeSkillToken(canonical), canonical);
            List<String> aliases = SKILL_ALIASES.get(canonical);
            if (aliases == null || aliases.isEmpty()) {
                continue;
            }
            for (String alias : aliases) {
                String normalizedAlias = normalizeSkillToken(alias);
                if (!normalizedAlias.isBlank()) {
                    map.put(normalizedAlias, canonical);
                }
            }
        }
        return Map.copyOf(map);
    }

    private static Map<String, Set<String>> buildCanonicalSearchTerms() {
        Map<String, Set<String>> terms = new LinkedHashMap<>();
        for (String canonical : CANONICAL_SKILLS) {
            LinkedHashSet<String> normalizedTerms = new LinkedHashSet<>();
            String canonicalToken = normalizeSkillToken(canonical);
            if (!canonicalToken.isBlank()) {
                normalizedTerms.add(canonicalToken);
            }
            List<String> aliases = SKILL_ALIASES.get(canonical);
            if (aliases != null) {
                for (String alias : aliases) {
                    String normalizedAlias = normalizeSkillToken(alias);
                    if (!normalizedAlias.isBlank()) {
                        normalizedTerms.add(normalizedAlias);
                    }
                }
            }
            terms.put(canonical, Set.copyOf(normalizedTerms));
        }
        return Map.copyOf(terms);
    }

    private static String normalizeSkillToken(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9+#]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static boolean containsNormalizedTerm(String normalizedText, String normalizedTerm) {
        if (normalizedText == null || normalizedText.isBlank() || normalizedTerm == null || normalizedTerm.isBlank()) {
            return false;
        }
        return (" " + normalizedText + " ").contains(" " + normalizedTerm + " ");
    }

    private static int countNormalizedTermOccurrences(String normalizedText, String normalizedTerm) {
        if (normalizedText == null || normalizedText.isBlank() || normalizedTerm == null || normalizedTerm.isBlank()) {
            return 0;
        }
        Pattern pattern = Pattern.compile("(?<![a-z0-9])" + Pattern.quote(normalizedTerm) + "(?![a-z0-9])");
        Matcher matcher = pattern.matcher(normalizedText);
        int count = 0;
        while (matcher.find()) {
            count += 1;
        }
        return count;
    }

    public record RoleDefinition(
            String title,
            List<String> skillSignals,
            List<String> keywordSignals,
            int minScore,
            Integer minYears) {
    }
}
