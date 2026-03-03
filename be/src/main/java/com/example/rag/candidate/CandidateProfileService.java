package com.example.rag.candidate;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class CandidateProfileService {

    private static final List<String> KNOWN_SKILLS = List.of(
            "java", "spring", "kotlin", "python", "typescript", "javascript", "react",
            "node", "postgresql", "mysql", "aws", "azure", "gcp", "docker", "kubernetes",
            "terraform", "ci/cd", "git", "rest", "graphql", "langchain", "llm", "rag",
            "qa", "testing", "selenium", "cypress", "playwright", "postman",
            "cucumber", "testng", "junit", "jenkins", "ansible", "linux",
            "bash", "prometheus", "grafana", "sre"
    );

    private static final Set<String> NAME_BLOCKLIST = Set.of(
            "summary", "experience", "education", "skills", "projects", "profile", "contact",
            "objective", "certifications", "resume", "curriculum", "vitae", "linkedin", "github"
    );

    private static final Set<String> NAME_ROLE_NOISE = Set.of(
            "engineer", "developer", "architect", "manager", "consultant", "intern", "software",
            "qa", "sdet", "tester", "devops", "platform", "cloud", "frontend", "backend",
            "full", "stack", "senior", "junior", "principal", "lead"
    );

    private static final Set<String> LOCATION_HINT_WORDS = Set.of(
            "remote", "hybrid", "onsite", "on", "site", "united", "states", "usa", "canada",
            "mexico", "costa", "rica", "spain", "germany", "france", "italy", "uk", "england",
            "brazil", "argentina", "colombia", "peru", "chile"
    );

    private static final Set<String> NAME_PARTICLES = Set.of(
            "de", "del", "la", "las", "los", "da", "dos", "do", "van", "von", "y"
    );

    private static final Set<String> US_STATE_CODES = Set.of(
            "AL", "AK", "AZ", "AR", "CA", "CO", "CT", "DC", "DE", "FL", "GA", "HI", "IA", "ID",
            "IL", "IN", "KS", "KY", "LA", "MA", "MD", "ME", "MI", "MN", "MO", "MS", "MT", "NC",
            "ND", "NE", "NH", "NJ", "NM", "NV", "NY", "OH", "OK", "OR", "PA", "PR", "RI", "SC",
            "SD", "TN", "TX", "UT", "VA", "VT", "WA", "WI", "WV", "WY"
    );

    private static final Set<String> LOCATION_INVALID_TERMS = Set.of(
            "postgres", "postgresql", "mysql", "mongodb", "oracle", "redis", "kafka",
            "spring", "java", "python", "react", "node", "aws", "azure", "gcp", "docker",
            "kubernetes", "terraform", "ci/cd", "graphql", "rest", "git", "sql"
    );

    private static final List<String> SUMMARY_KEYWORDS = List.of(
            "experience", "engineer", "developer", "architect", "built", "designed", "led",
            "implemented", "managed", "delivered", "specialized", "expertise", "skills"
    );

    private static final Set<String> PRINCIPAL_KEYWORDS = Set.of(
            "principal", "staff engineer", "staff software engineer"
    );

    private static final Set<String> SENIOR_KEYWORDS = Set.of(
            "senior", "lead", "tech lead", "architect", "manager"
    );
    private static final String QA_ROLE_TITLE = "QA / Test Engineer";
    private static final String TECH_LEAD_ROLE_TITLE = "Tech Lead";
    private static final String ENGINEERING_MANAGER_ROLE_TITLE = "Engineering Manager";
    private static final Set<String> QA_TRACK_KEYWORDS = Set.of(
            "qa", "quality assurance", "sdet", "test engineer", "software tester",
            "test automation", "automation testing", "manual testing", "regression testing",
            "api testing", "test cases"
    );
    private static final Set<String> TECH_LEAD_TRACK_KEYWORDS = Set.of(
            "tech lead", "team lead", "lead engineer", "lead developer", "led team",
            "architecture", "architected", "mentored", "technical leadership"
    );
    private static final Set<String> MANAGER_TRACK_KEYWORDS = Set.of(
            "engineering manager", "people manager", "line manager", "manager of",
            "managed team", "hiring", "performance review", "career development",
            "director", "head of engineering"
    );

    private static final int SIGNIFICANT_SKILLS_LIMIT = 8;
    private static final int SUGGESTED_ROLES_LIMIT = 3;
    private static final int VERSION_HISTORY_LIMIT = 30;

    private static final List<RoleDefinition> ROLE_DEFINITIONS = List.of(
            new RoleDefinition(
                    "Backend Engineer",
                    List.of("JAVA", "SPRING", "KOTLIN", "REST", "POSTGRESQL", "MYSQL"),
                    List.of("backend", "microservice", "api"),
                    3,
                    null
            ),
            new RoleDefinition(
                    "Full-Stack Engineer",
                    List.of("JAVASCRIPT", "TYPESCRIPT", "REACT", "NODE", "JAVA", "SPRING"),
                    List.of("full stack", "frontend and backend"),
                    4,
                    null
            ),
            new RoleDefinition(
                    "Frontend Engineer",
                    List.of("REACT", "JAVASCRIPT", "TYPESCRIPT", "GRAPHQL"),
                    List.of("frontend", "ui", "web app"),
                    3,
                    null
            ),
            new RoleDefinition(
                    "DevOps / Platform Engineer",
                    List.of("DOCKER", "KUBERNETES", "TERRAFORM", "AWS", "AZURE", "GCP", "CI/CD", "JENKINS", "ANSIBLE", "PROMETHEUS", "GRAFANA", "SRE"),
                    List.of("devops", "platform", "infrastructure", "sre", "observability", "site reliability"),
                    4,
                    null
            ),
            new RoleDefinition(
                    "QA / Test Engineer",
                    List.of("QA", "TESTING", "SELENIUM", "CYPRESS", "PLAYWRIGHT", "POSTMAN", "CUCUMBER", "TESTNG", "JUNIT"),
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
                    List.of("PYTHON", "LLM", "RAG", "LANGCHAIN"),
                    List.of("llm", "rag", "prompt", "retrieval"),
                    3,
                    null
            ),
            new RoleDefinition(
                    "Tech Lead",
                    List.of("JAVA", "SPRING", "AWS", "KUBERNETES", "CI/CD"),
                    List.of("tech lead", "team lead", "lead engineer", "lead developer", "architect", "architecture", "mentored", "led team"),
                    3,
                    6
            ),
            new RoleDefinition(
                    "Engineering Manager",
                    List.of("JAVA", "SPRING", "AWS", "KUBERNETES", "CI/CD"),
                    List.of("engineering manager", "people manager", "line manager", "managed team", "hiring", "performance review", "director"),
                    3,
                    8
            )
    );

    private static final Pattern YEARS_PATTERN = Pattern.compile("(\\d{1,2})\\+?\\s+years", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCATION_PATTERN = Pattern.compile("(?:location|based in)\\s*[:\\-]\\s*([^\\n,;]+(?:,\\s*[^\\n,;]+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CITY_STATE_PATTERN = Pattern.compile("([A-Z][a-z]+(?:[\\s\\-'][A-Z][a-z]+)*,\\s*([A-Z]{2}))");
    private static final Pattern NAME_TOKEN_PATTERN = Pattern.compile("^[\\p{L}][\\p{L}.\\-']*$");
    private static final Pattern NAME_SEGMENT_SPLIT_PATTERN = Pattern.compile("\\s(?:\\||•|·|—|–|:)\\s|\\s+-\\s");
    private static final Pattern NAME_WITH_ROLE_SUFFIX_PATTERN = Pattern.compile(
            "^([\\p{L}][\\p{L}.\\-']*(?:\\s+[\\p{L}][\\p{L}.\\-']*){1,3})\\s+"
                    + "(?:senior|junior|principal|lead|staff|software|qa|sdet|devops|backend|frontend|full[- ]?stack|"
                    + "engineering|engineer|developer|manager|architect)\\b.*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("([A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,})", Pattern.CASE_INSENSITIVE);
    private static final Pattern PHONE_PATTERN = Pattern.compile("(\\+?\\d[\\d\\s().\\-]{7,}\\d)");
    private static final Pattern URL_PATTERN = Pattern.compile("((?:https?://)?(?:www\\.)?(?:linkedin\\.com|github\\.com)/[^\\s)]+)", Pattern.CASE_INSENSITIVE);
    private static final Pattern WEB_URL_PATTERN = Pattern.compile("((?:https?://)?(?:www\\.)?[a-z0-9][a-z0-9.-]+\\.[a-z]{2,}(?:/[^\\s)]*)?)", Pattern.CASE_INSENSITIVE);

    private final Map<String, CandidateProfile> candidatesById = new ConcurrentHashMap<>();
    private final Map<String, String> candidateIdBySourceFilename = new ConcurrentHashMap<>();
    private final Map<String, String> candidateIdByContentHash = new ConcurrentHashMap<>();

    public void indexResume(String sourceFilename, Path resumePath, String text) {
        indexResume(sourceFilename, resumePath, text, computeContentHash(text));
    }

    public void indexResume(String sourceFilename, Path resumePath, String text, String contentHash) {
        String normalizedText = text == null ? "" : text.replace("\r", "\n");
        String displayName = extractDisplayName(sourceFilename, normalizedText);
        String email = extractEmail(normalizedText);
        String phone = extractPhone(normalizedText);
        String linkedinUrl = extractLinkedinUrl(normalizedText);
        String githubUrl = extractGithubUrl(normalizedText);
        String portfolioUrl = extractPortfolioUrl(normalizedText);
        SkillExtraction skillExtraction = extractSkillExtraction(normalizedText);
        List<String> skills = skillExtraction.skills();
        List<String> significantSkills = skillExtraction.significantSkills();
        Integer years = extractYearsExperience(normalizedText);
        String location = extractLocation(normalizedText);
        List<String> suggestedRoles = suggestRoles(normalizedText, skills, years);
        String preview = buildPreview(
                normalizedText,
                displayName,
                skills,
                significantSkills,
                suggestedRoles,
                years,
                location,
                email,
                phone,
                linkedinUrl,
                githubUrl,
                portfolioUrl);

        long fileSize = 0L;
        Instant modifiedAt = null;
        try {
            if (Files.exists(resumePath)) {
                fileSize = Files.size(resumePath);
                modifiedAt = Files.getLastModifiedTime(resumePath).toInstant();
            }
        } catch (IOException ignored) {
            // Keep defaults when metadata cannot be read.
        }

        String candidateId = resolveCandidateId(sourceFilename, displayName, email, phone, linkedinUrl, githubUrl, portfolioUrl);
        CandidateProfile existing = candidatesById.get(candidateId);
        CandidateProfile merged = mergeCandidate(
                existing,
                candidateId,
                sourceFilename,
                displayName,
                email,
                phone,
                linkedinUrl,
                githubUrl,
                portfolioUrl,
                skills,
                significantSkills,
                suggestedRoles,
                years,
                location,
                fileSize,
                modifiedAt,
                preview);

        candidatesById.put(candidateId, merged);
        candidateIdBySourceFilename.put(sourceFilename, candidateId);
        if (contentHash != null && !contentHash.isBlank()) {
            candidateIdByContentHash.putIfAbsent(contentHash, candidateId);
        }
    }

    public Optional<String> findDuplicateSource(String sourceFilename, String contentHash) {
        if (contentHash == null || contentHash.isBlank()) {
            return Optional.empty();
        }
        String candidateId = candidateIdByContentHash.get(contentHash);
        if (candidateId == null) {
            return Optional.empty();
        }
        CandidateProfile candidate = candidatesById.get(candidateId);
        if (candidate == null) {
            return Optional.empty();
        }
        if (candidate.sourceFilenames().stream().anyMatch(existing -> existing.equalsIgnoreCase(sourceFilename))) {
            return Optional.empty();
        }
        return Optional.ofNullable(candidate.sourceFilename());
    }

    public Optional<CandidateProfile> getById(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(candidatesById.get(id));
    }

    public Optional<CandidateProfile> getBySourceFilename(String sourceFilename) {
        if (sourceFilename == null) {
            return Optional.empty();
        }
        String id = candidateIdBySourceFilename.get(sourceFilename);
        if (id == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(candidatesById.get(id));
    }

    public List<CandidateProfile> allCandidates() {
        return candidatesById.values().stream()
                .sorted(Comparator.comparing(CandidateProfile::displayName, String.CASE_INSENSITIVE_ORDER))
                .toList();
    }

    public CandidateSearchResponse search(
            String search,
            String skill,
            String location,
            String sort,
            Integer page,
            Integer pageSize) {
        String query = normalize(search);
        String skillFilter = normalize(skill);
        String locationFilter = normalize(location);

        List<CandidateProfile> filtered = candidatesById.values().stream()
                .filter(candidate -> query.isEmpty()
                        || normalize(candidate.displayName()).contains(query)
                        || normalize(candidate.sourceFilename()).contains(query)
                        || candidate.sourceFilenames().stream().anyMatch(source -> normalize(source).contains(query))
                        || normalize(candidate.preview()).contains(query))
                .filter(candidate -> skillFilter.isEmpty()
                        || candidate.skills().stream().anyMatch(s -> normalize(s).contains(skillFilter)))
                .filter(candidate -> locationFilter.isEmpty()
                        || normalize(candidate.location()).contains(locationFilter))
                .sorted(resolveSort(sort))
                .toList();

        int safePageSize = pageSize != null && pageSize > 0 ? Math.min(pageSize, 100) : 20;
        int safePage = page != null && page > 0 ? page : 1;
        int total = filtered.size();
        int start = Math.min((safePage - 1) * safePageSize, total);
        int end = Math.min(start + safePageSize, total);
        List<CandidateProfile> items = start < end ? filtered.subList(start, end) : List.of();

        return new CandidateSearchResponse(items, safePage, safePageSize, total);
    }

    private static CandidateProfile mergeCandidate(
            CandidateProfile existing,
            String candidateId,
            String sourceFilename,
            String displayName,
            String email,
            String phone,
            String linkedinUrl,
            String githubUrl,
            String portfolioUrl,
            List<String> skills,
            List<String> significantSkills,
            List<String> suggestedRoles,
            Integer years,
            String location,
            long fileSize,
            Instant modifiedAt,
            String preview) {
        Instant now = Instant.now();
        if (existing == null) {
            return new CandidateProfile(
                    candidateId,
                    sourceFilename,
                    List.of(sourceFilename),
                    displayName,
                    email,
                    phone,
                    linkedinUrl,
                    githubUrl,
                    portfolioUrl,
                    skills,
                    significantSkills,
                    suggestedRoles,
                    years,
                    location,
                    fileSize,
                    modifiedAt,
                    now,
                    preview,
                    List.of(toVersionSnapshot(
                            sourceFilename,
                            now,
                            skills,
                            significantSkills,
                            suggestedRoles,
                            years,
                            location,
                            preview))
            );
        }

        Set<String> sourceFiles = new LinkedHashSet<>(existing.sourceFilenames());
        sourceFiles.add(sourceFilename);

        Set<String> mergedSkills = new LinkedHashSet<>(existing.skills());
        mergedSkills.addAll(skills);

        List<CandidateProfileVersion> versions = new ArrayList<>();
        versions.add(toVersionSnapshot(
                sourceFilename,
                now,
                skills,
                significantSkills,
                suggestedRoles,
                years,
                location,
                preview));
        versions.addAll(existing.versions() != null ? existing.versions() : List.of());
        if (versions.size() > VERSION_HISTORY_LIMIT) {
            versions = versions.subList(0, VERSION_HISTORY_LIMIT);
        }

        return new CandidateProfile(
                existing.id(),
                choosePreferredSource(existing.sourceFilename(), sourceFilename),
                List.copyOf(sourceFiles),
                chooseBetterDisplayName(existing.displayName(), displayName),
                pickBestContact(existing.email(), email),
                pickBestContact(existing.phone(), phone),
                pickBestContact(existing.linkedinUrl(), linkedinUrl),
                pickBestContact(existing.githubUrl(), githubUrl),
                pickBestContact(existing.portfolioUrl(), portfolioUrl),
                List.copyOf(mergedSkills),
                mergeRanked(significantSkills, existing.significantSkills(), SIGNIFICANT_SKILLS_LIMIT),
                mergeRanked(suggestedRoles, existing.suggestedRoles(), SUGGESTED_ROLES_LIMIT),
                chooseMax(existing.estimatedYearsExperience(), years),
                chooseText(existing.location(), location),
                Math.max(existing.fileSizeBytes(), fileSize),
                chooseLatest(existing.fileLastModifiedAt(), modifiedAt),
                now,
                chooseLonger(existing.preview(), preview),
                List.copyOf(versions)
        );
    }

    private static CandidateProfileVersion toVersionSnapshot(
            String sourceFilename,
            Instant ingestedAt,
            List<String> skills,
            List<String> significantSkills,
            List<String> suggestedRoles,
            Integer years,
            String location,
            String preview) {
        return new CandidateProfileVersion(
                sourceFilename,
                ingestedAt,
                skills != null ? List.copyOf(skills) : List.of(),
                significantSkills != null ? List.copyOf(significantSkills) : List.of(),
                suggestedRoles != null ? List.copyOf(suggestedRoles) : List.of(),
                years,
                location != null ? location : "",
                preview != null ? preview : ""
        );
    }

    private static String choosePreferredSource(String existingSource, String newSource) {
        if (existingSource == null || existingSource.isBlank()) {
            return newSource;
        }
        return existingSource;
    }

    private static String pickBestContact(String existingValue, String newValue) {
        return chooseText(existingValue, newValue);
    }

    private static String chooseText(String existingValue, String newValue) {
        if (existingValue != null && !existingValue.isBlank()) {
            return existingValue;
        }
        return newValue != null ? newValue : "";
    }

    private static String chooseLonger(String existingValue, String newValue) {
        String existing = existingValue != null ? existingValue : "";
        String newer = newValue != null ? newValue : "";
        return newer.length() > existing.length() ? newer : existing;
    }

    private static Integer chooseMax(Integer a, Integer b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return Math.max(a, b);
    }

    private static Instant chooseLatest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return b.isAfter(a) ? b : a;
    }

    private static String resolveCandidateId(
            String sourceFilename,
            String displayName,
            String email,
            String phone,
            String linkedinUrl,
            String githubUrl,
            String portfolioUrl) {
        String seed;
        if (!isBlank(email)) {
            seed = "email:" + email.toLowerCase(Locale.ROOT);
        } else if (!isBlank(linkedinUrl)) {
            seed = "linkedin:" + linkedinUrl.toLowerCase(Locale.ROOT);
        } else if (!isBlank(githubUrl)) {
            seed = "github:" + githubUrl.toLowerCase(Locale.ROOT);
        } else if (!isBlank(portfolioUrl)) {
            seed = "portfolio:" + portfolioUrl.toLowerCase(Locale.ROOT);
        } else if (!isBlank(phone)) {
            seed = "phone:" + phone.replaceAll("\\D", "");
        } else if (!isBlank(displayName)) {
            seed = "name:" + displayName.toLowerCase(Locale.ROOT);
        } else {
            seed = sourceFilename != null ? sourceFilename : "candidate";
        }

        String slug = seed.replaceAll("[^a-zA-Z0-9]+", "-").toLowerCase(Locale.ROOT);
        slug = slug.replaceAll("^-+|-+$", "");
        return slug.isBlank() ? toCandidateId(sourceFilename) : slug;
    }

    private static String toCandidateId(String sourceFilename) {
        String base = sourceFilename == null ? "candidate" : sourceFilename;
        int extensionIndex = base.lastIndexOf('.');
        if (extensionIndex > 0) {
            base = base.substring(0, extensionIndex);
        }
        String slug = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        slug = slug.replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "candidate" : slug;
    }

    private static Comparator<CandidateProfile> resolveSort(String sort) {
        String normalized = normalize(sort);
        return switch (normalized) {
            case "years_desc" -> Comparator.comparing(
                    (CandidateProfile c) -> c.estimatedYearsExperience() != null ? c.estimatedYearsExperience() : 0
            ).reversed().thenComparing(CandidateProfile::displayName, String.CASE_INSENSITIVE_ORDER);
            case "skills_desc" -> Comparator.comparing((CandidateProfile c) -> c.skills() != null ? c.skills().size() : 0)
                    .reversed()
                    .thenComparing(CandidateProfile::displayName, String.CASE_INSENSITIVE_ORDER);
            case "ingested_desc" -> Comparator.comparing(
                    CandidateProfile::lastIngestedAt,
                    Comparator.nullsLast(Comparator.naturalOrder())
            ).reversed().thenComparing(CandidateProfile::displayName, String.CASE_INSENSITIVE_ORDER);
            case "name_desc" -> Comparator.comparing(CandidateProfile::displayName, String.CASE_INSENSITIVE_ORDER).reversed();
            default -> Comparator.comparing(CandidateProfile::displayName, String.CASE_INSENSITIVE_ORDER);
        };
    }

    private static String extractDisplayName(String filename, String text) {
        List<String> candidateLines = Arrays.stream(text.split("\\n"))
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .limit(20)
                .toList();

        String bestName = "";
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < candidateLines.size(); i++) {
            for (String lineVariant : expandCandidateLineVariants(candidateLines.get(i))) {
                String candidate = normalizeCandidateName(lineVariant);
                if (candidate.isBlank()) {
                    continue;
                }
                double score = scoreNameCandidate(candidate, i);
                if (score > bestScore) {
                    bestScore = score;
                    bestName = candidate;
                }
            }
        }

        if (!bestName.isBlank()) {
            return bestName;
        }

        String fromEmail = nameFromEmail(text);
        if (!fromEmail.isBlank()) {
            return fromEmail;
        }

        return nameFromFilename(filename);
    }

    private static List<String> expandCandidateLineVariants(String line) {
        if (line == null || line.isBlank()) {
            return List.of();
        }
        String trimmed = line.trim();
        LinkedHashSet<String> variants = new LinkedHashSet<>();
        variants.add(trimmed);

        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("name:")) {
            variants.add(trimmed.substring(5).trim());
        } else if (lower.startsWith("name -")) {
            variants.add(trimmed.substring(6).trim());
        }

        for (String segment : NAME_SEGMENT_SPLIT_PATTERN.split(trimmed)) {
            String compact = segment.trim();
            if (!compact.isBlank()) {
                variants.add(compact);
            }
        }
        return variants.stream().toList();
    }

    private static String normalizeCandidateName(String line) {
        if (line == null) {
            return "";
        }
        String compact = line.replaceAll("\\s+", " ").trim();
        if (compact.isBlank() || compact.length() > 80) {
            return "";
        }
        compact = stripRoleSuffixFromNameLine(compact);
        if (compact.contains("@") || compact.matches(".*\\d.*") || compact.toLowerCase(Locale.ROOT).contains("http")) {
            return "";
        }
        if (looksLikeLocationPhrase(compact)) {
            return "";
        }

        List<String> lineWords = Arrays.stream(compact.toLowerCase(Locale.ROOT).split("[^\\p{L}]+"))
                .filter(word -> !word.isBlank())
                .toList();
        if (lineWords.stream().anyMatch(NAME_BLOCKLIST::contains)) {
            return "";
        }
        if (lineWords.stream().anyMatch(NAME_ROLE_NOISE::contains)) {
            return "";
        }

        List<String> rawTokens = Arrays.stream(compact.replace(',', ' ').replace(';', ' ').split("\\s+"))
                .filter(token -> !token.isBlank())
                .toList();
        List<String> normalizedRawTokens = collapseSingleLetterRuns(rawTokens);
        String[] normalizedTokensArray = normalizedRawTokens.toArray(new String[0]);
        if (normalizedTokensArray.length < 2 || normalizedTokensArray.length > 6) {
            return "";
        }

        List<String> normalizedTokens = new ArrayList<>();
        for (int i = 0; i < normalizedTokensArray.length; i++) {
            String token = normalizedTokensArray[i].replaceAll("^[^\\p{L}]+|[^\\p{L}.\\-']+$", "");
            if (token.isBlank() || !NAME_TOKEN_PATTERN.matcher(token).matches()) {
                return "";
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if (i > 0 && NAME_PARTICLES.contains(lower)) {
                normalizedTokens.add(lower);
            } else {
                normalizedTokens.add(titleCaseToken(lower));
            }
        }

        return String.join(" ", normalizedTokens).trim();
    }

    private static String stripRoleSuffixFromNameLine(String line) {
        if (line == null || line.isBlank()) {
            return "";
        }
        Matcher matcher = NAME_WITH_ROLE_SUFFIX_PATTERN.matcher(line.trim());
        if (!matcher.matches()) {
            return line.trim();
        }
        return matcher.group(1).trim();
    }

    private static List<String> collapseSingleLetterRuns(List<String> tokens) {
        List<String> collapsed = new ArrayList<>();
        StringBuilder letterRun = new StringBuilder();
        for (String rawToken : tokens) {
            String token = rawToken.replaceAll("^[^\\p{L}]+|[^\\p{L}.\\-']+$", "");
            if (token.length() == 1 && Character.isLetter(token.charAt(0))) {
                letterRun.append(token);
                continue;
            }
            flushLetterRun(collapsed, letterRun);
            if (!token.isBlank()) {
                collapsed.add(token);
            }
        }
        flushLetterRun(collapsed, letterRun);
        return collapsed;
    }

    private static void flushLetterRun(List<String> target, StringBuilder letterRun) {
        if (letterRun.length() >= 2) {
            target.add(letterRun.toString());
        } else if (letterRun.length() == 1) {
            target.add(letterRun.toString() + ".");
        }
        letterRun.setLength(0);
    }

    private static double scoreNameCandidate(String name, int lineIndex) {
        String[] tokens = name.split("\\s+");
        int tokenCount = tokens.length;
        double score = 100.0 - (lineIndex * 4.0);
        if (tokenCount == 2 || tokenCount == 3) {
            score += 8.0;
        } else if (tokenCount == 4) {
            score += 4.0;
        }
        long particleCount = Arrays.stream(tokens)
                .map(token -> token.toLowerCase(Locale.ROOT))
                .filter(NAME_PARTICLES::contains)
                .count();
        score += Math.min(2.0, particleCount);
        if (looksLikeLocationPhrase(name)) {
            score -= 20.0;
        }
        return score;
    }

    private static String titleCaseToken(String token) {
        String lower = token.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        boolean capitalizeNext = true;
        for (int i = 0; i < lower.length(); i++) {
            char ch = lower.charAt(i);
            if (ch == '-' || ch == '\'' || ch == '.') {
                sb.append(ch);
                capitalizeNext = true;
                continue;
            }
            if (capitalizeNext && Character.isLetter(ch)) {
                sb.append(Character.toUpperCase(ch));
                capitalizeNext = false;
            } else {
                sb.append(ch);
                capitalizeNext = false;
            }
        }
        return sb.toString();
    }

    private static String nameFromFilename(String filename) {
        String base = filename == null ? "Candidate" : filename;
        int extensionIndex = base.lastIndexOf('.');
        if (extensionIndex > 0) {
            base = base.substring(0, extensionIndex);
        }
        String[] tokens = base.replace('_', ' ').replace('-', ' ').trim().split("\\s+");
        List<String> words = new ArrayList<>();
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i].replaceAll("[^\\p{L}]", "");
            if (token.isBlank()) {
                continue;
            }
            String lower = token.toLowerCase(Locale.ROOT);
            if (NAME_BLOCKLIST.contains(lower)
                    || "email".equals(lower)
                    || "gmail".equals(lower)
                    || "yahoo".equals(lower)
                    || "hotmail".equals(lower)
                    || "outlook".equals(lower)
                    || "resume".equals(lower)
                    || "cv".equals(lower)
                    || "com".equals(lower)
                    || "net".equals(lower)
                    || "org".equals(lower)) {
                continue;
            }
            words.add(i > 0 && NAME_PARTICLES.contains(lower) ? lower : titleCaseToken(lower));
        }
        String result = String.join(" ", words).trim();
        return result.isEmpty() ? "Candidate" : result;
    }

    private static String nameFromEmail(String text) {
        String email = extractEmail(text);
        if (email.isBlank()) {
            return "";
        }
        int atIndex = email.indexOf('@');
        if (atIndex <= 0) {
            return "";
        }
        String local = email.substring(0, atIndex).toLowerCase(Locale.ROOT);
        String[] rawTokens = local.replaceAll("[^\\p{L}]+", " ").trim().split("\\s+");
        List<String> tokens = Arrays.stream(rawTokens)
                .filter(token -> !token.isBlank())
                .map(token -> token.replaceAll("\\d+", ""))
                .filter(token -> !token.isBlank())
                .toList();
        if (tokens.size() < 2 || tokens.size() > 5) {
            return "";
        }
        List<String> nameTokens = new ArrayList<>();
        for (int i = 0; i < tokens.size(); i++) {
            String token = tokens.get(i);
            if (!NAME_TOKEN_PATTERN.matcher(token).matches()) {
                return "";
            }
            String lower = token.toLowerCase(Locale.ROOT);
            nameTokens.add(i > 0 && NAME_PARTICLES.contains(lower) ? lower : titleCaseToken(lower));
        }
        return String.join(" ", nameTokens).trim();
    }

    private static boolean looksLikeLocationPhrase(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String compact = value.trim();
        String lower = compact.toLowerCase(Locale.ROOT);
        if (lower.contains("remote") || lower.contains("hybrid") || lower.contains("onsite") || lower.contains("on-site")) {
            return true;
        }
        Matcher cityStateMatcher = CITY_STATE_PATTERN.matcher(compact);
        if (cityStateMatcher.find()) {
            String stateCode = cityStateMatcher.group(2).trim().toUpperCase(Locale.ROOT);
            if (US_STATE_CODES.contains(stateCode)) {
                return true;
            }
        }
        List<String> words = Arrays.stream(lower.split("[^\\p{L}]+"))
                .filter(word -> !word.isBlank())
                .toList();
        if (words.size() < 2 || words.size() > 5) {
            return false;
        }
        long hints = words.stream().filter(LOCATION_HINT_WORDS::contains).count();
        return hints >= 2;
    }

    private static SkillExtraction extractSkillExtraction(String text) {
        Map<String, Integer> counts = extractSkillCounts(text);
        List<String> ordered = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(entry -> entry.getKey().toUpperCase(Locale.ROOT))
                .toList();

        List<String> significant = ordered.stream()
                .limit(SIGNIFICANT_SKILLS_LIMIT)
                .toList();

        return new SkillExtraction(ordered, significant);
    }

    private static Map<String, Integer> extractSkillCounts(String text) {
        String lower = normalize(text);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String skill : KNOWN_SKILLS) {
            int occurrences = countOccurrences(lower, skill.toLowerCase(Locale.ROOT));
            if (occurrences > 0) {
                counts.put(skill, occurrences);
            }
        }
        return counts;
    }

    private static int countOccurrences(String text, String skill) {
        Pattern pattern = Pattern.compile("(?<![a-z0-9])" + Pattern.quote(skill) + "(?![a-z0-9])");
        Matcher matcher = pattern.matcher(text);
        int count = 0;
        while (matcher.find()) {
            count += 1;
        }
        return count;
    }

    private static List<String> suggestRoles(String text, List<String> skills, Integer years) {
        Set<String> skillSet = new LinkedHashSet<>();
        for (String skill : skills) {
            if (skill != null && !skill.isBlank()) {
                skillSet.add(skill.toUpperCase(Locale.ROOT));
            }
        }
        String lowerText = normalize(text);

        List<RoleMatch> matches = new ArrayList<>();
        for (RoleDefinition definition : ROLE_DEFINITIONS) {
            int skillHits = 0;
            for (String signal : definition.skillSignals()) {
                if (skillSet.contains(signal)) {
                    skillHits += 1;
                }
            }

            int keywordHits = 0;
            for (String keyword : definition.keywordSignals()) {
                if (lowerText.contains(keyword)) {
                    keywordHits += 1;
                }
            }

            int score = skillHits * 2 + keywordHits;
            if (definition.minYears() != null && years != null && years < definition.minYears()) {
                score -= 1;
            }

            if (QA_ROLE_TITLE.equals(definition.title())
                    && skillHits < 2
                    && !containsAny(lowerText, QA_TRACK_KEYWORDS)) {
                continue;
            }

            if (TECH_LEAD_ROLE_TITLE.equals(definition.title())
                    && !containsAny(lowerText, TECH_LEAD_TRACK_KEYWORDS)) {
                continue;
            }

            if (ENGINEERING_MANAGER_ROLE_TITLE.equals(definition.title())
                    && !containsAny(lowerText, MANAGER_TRACK_KEYWORDS)) {
                continue;
            }

            boolean qualifies = score >= definition.minScore() && (skillHits > 0 || keywordHits > 1);
            if (qualifies) {
                matches.add(new RoleMatch(definition.title(), score, skillHits, keywordHits));
            }
        }

        List<String> suggested = matches.stream()
                .sorted(Comparator.comparingInt(RoleMatch::score).reversed()
                        .thenComparing(Comparator.comparingInt(RoleMatch::skillHits).reversed())
                        .thenComparing(Comparator.comparingInt(RoleMatch::keywordHits).reversed())
                        .thenComparing(RoleMatch::title, String.CASE_INSENSITIVE_ORDER))
                .map(RoleMatch::title)
                .limit(SUGGESTED_ROLES_LIMIT)
                .toList();

        String seniority = inferSeniorityLabel(lowerText, years);
        if (!suggested.isEmpty()) {
            return suggested.stream()
                    .map(role -> withSeniority(role, seniority))
                    .toList();
        }
        return List.of(withSeniority("Software Engineer", seniority));
    }

    private static String inferSeniorityLabel(String lowerText, Integer years) {
        if (containsAny(lowerText, PRINCIPAL_KEYWORDS) || (years != null && years >= 12)) {
            return "Principal";
        }
        if (containsAny(lowerText, SENIOR_KEYWORDS) || (years != null && years >= 7)) {
            return "Senior";
        }
        if (years != null && years >= 3) {
            return "Intermediate";
        }
        return "Junior";
    }

    private static boolean containsAny(String text, Set<String> terms) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return terms.stream().anyMatch(text::contains);
    }

    private static String withSeniority(String role, String seniority) {
        if (role == null || role.isBlank()) {
            return seniority + " Software Engineer";
        }
        if (seniority == null || seniority.isBlank()) {
            return role;
        }
        return seniority + " " + role;
    }

    private static Integer extractYearsExperience(String text) {
        Matcher matcher = YEARS_PATTERN.matcher(text);
        int max = 0;
        while (matcher.find()) {
            try {
                max = Math.max(max, Integer.parseInt(matcher.group(1)));
            } catch (NumberFormatException ignored) {
                // Ignore malformed matches.
            }
        }
        return max > 0 ? max : null;
    }

    private static String extractLocation(String text) {
        Matcher matcher = LOCATION_PATTERN.matcher(text);
        if (matcher.find()) {
            String explicit = matcher.group(1).trim();
            return isLikelyLocation(explicit) ? explicit : "";
        }
        Matcher cityStateMatcher = CITY_STATE_PATTERN.matcher(text);
        if (cityStateMatcher.find()) {
            String candidate = cityStateMatcher.group(1).trim();
            String stateCode = cityStateMatcher.group(2).trim().toUpperCase(Locale.ROOT);
            if (US_STATE_CODES.contains(stateCode) && isLikelyLocation(candidate)) {
                return candidate;
            }
        }
        return "";
    }

    private static boolean isLikelyLocation(String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        String lower = value.toLowerCase(Locale.ROOT);
        return LOCATION_INVALID_TERMS.stream().noneMatch(lower::contains);
    }

    private static String buildPreview(
            String text,
            String displayName,
            List<String> skills,
            List<String> significantSkills,
            List<String> suggestedRoles,
            Integer years,
            String location,
            String email,
            String phone,
            String linkedinUrl,
            String githubUrl,
            String portfolioUrl) {
        String compact = text.replaceAll("\\s+", " ").trim();
        List<String> sections = new ArrayList<>();

        if (displayName != null && !displayName.isBlank()) {
            sections.add(displayName + " profile summary.");
        }
        if (years != null) {
            sections.add("Estimated experience: " + years + "+ years.");
        }
        if (location != null && !location.isBlank()) {
            sections.add("Location: " + location + ".");
        }
        if (email != null && !email.isBlank()) {
            sections.add("Email: " + email + ".");
        }
        if (phone != null && !phone.isBlank()) {
            sections.add("Phone: " + phone + ".");
        }
        if (linkedinUrl != null && !linkedinUrl.isBlank()) {
            sections.add("LinkedIn: " + linkedinUrl + ".");
        }
        if (githubUrl != null && !githubUrl.isBlank()) {
            sections.add("GitHub: " + githubUrl + ".");
        }
        if (portfolioUrl != null && !portfolioUrl.isBlank()) {
            sections.add("Portfolio: " + portfolioUrl + ".");
        }
        List<String> previewSkills = significantSkills != null && !significantSkills.isEmpty() ? significantSkills : skills;
        if (previewSkills != null && !previewSkills.isEmpty()) {
            sections.add("Core skills: " + String.join(", ", previewSkills.stream().limit(SIGNIFICANT_SKILLS_LIMIT).toList()) + ".");
        }
        if (suggestedRoles != null && !suggestedRoles.isEmpty()) {
            sections.add("Role alignment: " + String.join(", ", suggestedRoles) + ".");
        }

        List<String> highlights = extractSummaryHighlights(compact);
        if (!highlights.isEmpty()) {
            sections.add("Highlights: " + String.join(" ", highlights));
        } else if (!compact.isBlank()) {
            sections.add("Highlights: " + truncate(compact, 240));
        }

        String summary = String.join(" ", sections).replaceAll("\\s+", " ").trim();
        return truncate(summary, 600);
    }

    private static List<String> extractSummaryHighlights(String compactText) {
        if (compactText == null || compactText.isBlank()) {
            return List.of();
        }

        List<String> sentences = Arrays.stream(compactText.split("(?<=[.!?])\\s+"))
                .map(String::trim)
                .filter(sentence -> sentence.length() >= 40)
                .toList();

        List<String> priority = sentences.stream()
                .filter(CandidateProfileService::containsSummaryKeyword)
                .limit(2)
                .map(sentence -> truncate(sentence, 220))
                .toList();

        if (!priority.isEmpty()) {
            return priority;
        }

        return sentences.stream()
                .limit(2)
                .map(sentence -> truncate(sentence, 220))
                .toList();
    }

    private static boolean containsSummaryKeyword(String sentence) {
        String lower = sentence.toLowerCase(Locale.ROOT);
        return SUMMARY_KEYWORDS.stream().anyMatch(lower::contains);
    }

    private static String truncate(String text, int maxChars) {
        if (text == null) {
            return "";
        }
        String compact = text.replaceAll("\\s+", " ").trim();
        if (compact.length() <= maxChars) {
            return compact;
        }
        return compact.substring(0, maxChars).trim() + "...";
    }

    private static String extractEmail(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher matcher = EMAIL_PATTERN.matcher(text);
        return matcher.find() ? matcher.group(1).trim() : "";
    }

    private static String extractPhone(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher matcher = PHONE_PATTERN.matcher(text);
        while (matcher.find()) {
            String normalized = normalizePhoneNumber(matcher.group(1));
            if (!normalized.isBlank()) {
                return normalized;
            }
        }
        return "";
    }

    private static String normalizePhoneNumber(String rawPhone) {
        if (rawPhone == null || rawPhone.isBlank()) {
            return "";
        }
        String compact = rawPhone.trim().replaceAll("\\s+", " ");
        compact = compact.replaceAll("(?i)(?:ext\\.?|extension|x)\\s*\\d{1,5}\\s*$", "").trim();
        if (compact.isBlank()) {
            return "";
        }

        boolean hasPlusPrefix = compact.startsWith("+");
        String digits = compact.replaceAll("\\D", "");
        if (digits.length() < 10 || digits.length() > 15) {
            return "";
        }

        if (hasPlusPrefix) {
            return "+" + digits;
        }
        if (digits.length() == 10) {
            // Default to US country code when only a 10-digit local number is provided.
            return "+1" + digits;
        }
        return "+" + digits;
    }

    private static String extractLinkedinUrl(String text) {
        return extractUrlByHost(text, "linkedin.com");
    }

    private static String extractGithubUrl(String text) {
        return extractUrlByHost(text, "github.com");
    }

    private static String extractPortfolioUrl(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher matcher = WEB_URL_PATTERN.matcher(text);
        while (matcher.find()) {
            int start = matcher.start(1);
            int end = matcher.end(1);
            if (start > 0 && text.charAt(start - 1) == '@') {
                continue;
            }
            if (end < text.length() && text.charAt(end) == '@') {
                continue;
            }
            String raw = matcher.group(1).trim();
            String lower = raw.toLowerCase(Locale.ROOT);
            if (lower.contains("linkedin.com") || lower.contains("github.com")) {
                continue;
            }
            if (lower.startsWith("mailto:")) {
                continue;
            }
            return raw.startsWith("http://") || raw.startsWith("https://")
                    ? raw
                    : "https://" + raw.replaceFirst("^www\\.", "");
        }
        return "";
    }

    private static String extractUrlByHost(String text, String host) {
        if (text == null || text.isBlank()) {
            return "";
        }
        Matcher matcher = URL_PATTERN.matcher(text);
        while (matcher.find()) {
            String raw = matcher.group(1).trim();
            String lower = raw.toLowerCase(Locale.ROOT);
            if (lower.contains(host)) {
                return raw.startsWith("http://") || raw.startsWith("https://")
                        ? raw
                        : "https://" + raw.replaceFirst("^www\\.", "");
            }
        }
        return "";
    }

    private static String computeContentHash(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(text.replaceAll("\\s+", " ").trim().getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return "";
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String chooseBetterDisplayName(String existingName, String newName) {
        if (isBlank(existingName)) {
            return newName;
        }
        if (isBlank(newName)) {
            return existingName;
        }
        if (existingName.equalsIgnoreCase("candidate") && !newName.equalsIgnoreCase("candidate")) {
            return newName;
        }
        if (newName.split("\\s+").length > existingName.split("\\s+").length) {
            return newName;
        }
        return existingName;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).trim();
    }

    private static List<String> mergeRanked(List<String> preferred, List<String> secondary, int limit) {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (preferred != null) {
            preferred.stream().filter(value -> value != null && !value.isBlank()).forEach(merged::add);
        }
        if (secondary != null) {
            secondary.stream().filter(value -> value != null && !value.isBlank()).forEach(merged::add);
        }
        return merged.stream().limit(limit).toList();
    }

    private record SkillExtraction(List<String> skills, List<String> significantSkills) {
    }

    private record RoleDefinition(
            String title,
            List<String> skillSignals,
            List<String> keywordSignals,
            int minScore,
            Integer minYears) {
    }

    private record RoleMatch(String title, int score, int skillHits, int keywordHits) {
    }
}
