package com.example.rag.feature.candidate.service;

import com.example.rag.feature.candidate.domain.*;
import com.example.rag.feature.candidate.model.*;

import com.example.rag.feature.metrics.service.ObservabilityService;
import com.example.rag.feature.role.domain.TechnicalRoleCatalog;
import org.springframework.beans.factory.annotation.Autowired;
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

    private static final List<String> KNOWN_SKILLS = TechnicalRoleCatalog.canonicalSkills();

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
    private static final int SIGNIFICANT_SKILLS_LIMIT = 8;
    private static final int SUGGESTED_ROLES_LIMIT = 3;
    private static final int VERSION_HISTORY_LIMIT = 30;

    private static final Pattern YEARS_PATTERN = Pattern.compile("(\\d{1,2})\\+?\\s+years", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCATION_PATTERN = Pattern.compile("(?:location|based in)\\s*[:\\-]\\s*([^\\n,;]+(?:,\\s*[^\\n,;]+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern CITY_STATE_PATTERN = Pattern.compile("([A-Z][a-z]+(?:[\\s\\-'][A-Z][a-z]+)*,\\s*([A-Z]{2}))");
    private final Map<String, CandidateProfile> candidatesById = new ConcurrentHashMap<>();
    private final Map<String, String> candidateIdBySourceFilename = new ConcurrentHashMap<>();
    private final Map<String, String> candidateIdByContentHash = new ConcurrentHashMap<>();
    private ResumeLlmEnrichmentService llmEnrichmentService;
    private ObservabilityService observabilityService;

    @Autowired(required = false)
    void setLlmEnrichmentService(ResumeLlmEnrichmentService llmEnrichmentService) {
        this.llmEnrichmentService = llmEnrichmentService;
    }

    @Autowired(required = false)
    void setObservabilityService(ObservabilityService observabilityService) {
        this.observabilityService = observabilityService;
    }

    public void indexResume(String sourceFilename, Path resumePath, String text) {
        indexResume(sourceFilename, resumePath, text, computeContentHash(text));
    }

    public void indexResume(String sourceFilename, Path resumePath, String text, String contentHash) {
        String normalizedText = text == null ? "" : text.replace("\r", "\n");
        String normalizedContentHash = contentHash != null && !contentHash.isBlank()
                ? contentHash
                : computeContentHash(normalizedText);

        String displayName = CandidateNameExtractor.extractDisplayName(sourceFilename, normalizedText);
        String email = CandidateContactExtractor.extractEmail(normalizedText);
        String phone = CandidateContactExtractor.extractPhone(normalizedText);
        String linkedinUrl = CandidateContactExtractor.extractLinkedinUrl(normalizedText);
        String githubUrl = CandidateContactExtractor.extractGithubUrl(normalizedText);
        String portfolioUrl = CandidateContactExtractor.extractPortfolioUrl(normalizedText);
        SkillExtraction skillExtraction = extractSkillExtraction(normalizedText);
        List<String> skills = skillExtraction.skills();
        List<String> significantSkills = skillExtraction.significantSkills();
        Integer years = extractYearsExperience(normalizedText);
        String location = extractLocation(normalizedText);
        List<String> suggestedRoles = suggestRoles(normalizedText, skills, years);
        List<String> validationWarnings = new ArrayList<>();
        Map<String, Double> fieldConfidence = new LinkedHashMap<>();
        Map<String, String> fieldEvidence = new LinkedHashMap<>();
        String llmSummary = "";
        String extractionMethod = "rules-only";
        boolean llmAttempted = false;
        boolean llmFailed = false;

        if (llmEnrichmentService != null && llmEnrichmentService.isEnabled()) {
            llmAttempted = true;
            Optional<ResumeLlmEnrichmentService.LlmProfileEnrichment> llmEnrichment = llmEnrichmentService.enrich(normalizedText);
            if (llmEnrichment.isPresent()) {
                ResumeLlmEnrichmentService.LlmProfileEnrichment llm = llmEnrichment.get();
                if (shouldPreferLlmDisplayName(displayName, sourceFilename) && isPlausibleDisplayName(llm.displayName())) {
                    displayName = llm.displayName();
                }
                skills = mergeRanked(skills, llm.skills(), 20);
                significantSkills = mergeRanked(llm.significantSkills(), significantSkills, SIGNIFICANT_SKILLS_LIMIT);
                suggestedRoles = mergeRanked(llm.suggestedRoles(), suggestedRoles, SUGGESTED_ROLES_LIMIT);
                years = chooseYearsValue(years, llm.estimatedYearsExperience(), llm.fieldConfidence().get("estimatedYearsExperience"));
                if ((location == null || location.isBlank()) && llm.location() != null && isLikelyLocation(llm.location())) {
                    location = llm.location();
                }
                llmSummary = llm.summary() != null ? llm.summary() : "";
                fieldConfidence.putAll(llm.fieldConfidence());
                fieldEvidence.putAll(llm.fieldEvidence());
                extractionMethod = "hybrid-llm-rules";
            } else {
                llmFailed = true;
                extractionMethod = "rules-fallback";
                validationWarnings.add("LLM enrichment unavailable or invalid output; deterministic extraction applied.");
            }
        }

        addDeterministicEvidenceAndConfidence(
                fieldConfidence,
                fieldEvidence,
                displayName,
                email,
                phone,
                linkedinUrl,
                githubUrl,
                portfolioUrl,
                years,
                location,
                suggestedRoles
        );
        addValidationWarnings(validationWarnings, displayName, email, phone, skills, significantSkills, suggestedRoles);

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
                portfolioUrl,
                llmSummary);

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
                preview,
                extractionMethod,
                normalizedContentHash,
                normalizedText.length(),
                fieldConfidence,
                fieldEvidence,
                validationWarnings);

        candidatesById.put(candidateId, merged);
        candidateIdBySourceFilename.put(sourceFilename, candidateId);
        if (!normalizedContentHash.isBlank()) {
            candidateIdByContentHash.putIfAbsent(normalizedContentHash, candidateId);
        }
        if (observabilityService != null) {
            observabilityService.recordCandidateExtraction(llmAttempted, llmFailed, validationWarnings.size());
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
            String preview,
            String extractionMethod,
            String normalizedContentHash,
            int normalizedTextChars,
            Map<String, Double> fieldConfidence,
            Map<String, String> fieldEvidence,
            List<String> validationWarnings) {
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
                            preview,
                            extractionMethod,
                            normalizedContentHash,
                            normalizedTextChars,
                            fieldConfidence,
                            fieldEvidence,
                            validationWarnings))
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
                preview,
                extractionMethod,
                normalizedContentHash,
                normalizedTextChars,
                fieldConfidence,
                fieldEvidence,
                validationWarnings));
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
            String preview,
            String extractionMethod,
            String normalizedContentHash,
            int normalizedTextChars,
            Map<String, Double> fieldConfidence,
            Map<String, String> fieldEvidence,
            List<String> validationWarnings) {
        return new CandidateProfileVersion(
                sourceFilename,
                ingestedAt,
                skills != null ? List.copyOf(skills) : List.of(),
                significantSkills != null ? List.copyOf(significantSkills) : List.of(),
                suggestedRoles != null ? List.copyOf(suggestedRoles) : List.of(),
                years,
                location != null ? location : "",
                preview != null ? preview : "",
                extractionMethod != null ? extractionMethod : "rules-only",
                normalizedContentHash != null ? normalizedContentHash : "",
                Math.max(0, normalizedTextChars),
                fieldConfidence != null ? Map.copyOf(fieldConfidence) : Map.of(),
                fieldEvidence != null ? Map.copyOf(fieldEvidence) : Map.of(),
                validationWarnings != null ? List.copyOf(validationWarnings) : List.of()
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

    private static boolean shouldPreferLlmDisplayName(String currentDisplayName, String sourceFilename) {
        if (currentDisplayName == null || currentDisplayName.isBlank() || "Candidate".equalsIgnoreCase(currentDisplayName)) {
            return true;
        }
        String fromFilename = CandidateNameExtractor.nameFromFilename(sourceFilename);
        if (fromFilename.equalsIgnoreCase(currentDisplayName)) {
            return true;
        }
        String normalized = currentDisplayName.toLowerCase(Locale.ROOT);
        boolean emailLikeOrUsernameLike = normalized.contains("gmail")
                || normalized.contains("yahoo")
                || normalized.contains("hotmail")
                || normalized.contains("outlook");
        if (emailLikeOrUsernameLike) {
            return true;
        }
        return CandidateNameExtractor.looksLikeSkillOrRolePhrase(currentDisplayName, KNOWN_SKILLS);
    }

    private static boolean isPlausibleDisplayName(String value) {
        return CandidateNameExtractor.isPlausibleDisplayName(value);
    }

    private static Integer chooseYearsValue(Integer deterministicYears, Integer llmYears, Double llmConfidence) {
        if (llmYears == null || llmYears < 0 || llmYears > 60) {
            return deterministicYears;
        }
        if (deterministicYears == null || deterministicYears <= 0) {
            return llmYears;
        }
        double confidence = llmConfidence != null ? llmConfidence : 0.0d;
        if (Math.abs(deterministicYears - llmYears) <= 2 && confidence >= 0.8d) {
            return Math.max(deterministicYears, llmYears);
        }
        return deterministicYears;
    }

    private static void addDeterministicEvidenceAndConfidence(
            Map<String, Double> fieldConfidence,
            Map<String, String> fieldEvidence,
            String displayName,
            String email,
            String phone,
            String linkedinUrl,
            String githubUrl,
            String portfolioUrl,
            Integer years,
            String location,
            List<String> suggestedRoles) {
        if (fieldConfidence == null || fieldEvidence == null) {
            return;
        }
        if (displayName != null && !displayName.isBlank()) {
            fieldConfidence.putIfAbsent("displayName", 0.92d);
            fieldEvidence.putIfAbsent("displayName", truncate(displayName, 120));
        }
        if (email != null && !email.isBlank()) {
            fieldConfidence.putIfAbsent("email", 0.99d);
            fieldEvidence.putIfAbsent("email", email);
        }
        if (phone != null && !phone.isBlank()) {
            fieldConfidence.putIfAbsent("phone", 0.96d);
            fieldEvidence.putIfAbsent("phone", phone);
        }
        if (linkedinUrl != null && !linkedinUrl.isBlank()) {
            fieldConfidence.putIfAbsent("linkedinUrl", 0.99d);
            fieldEvidence.putIfAbsent("linkedinUrl", truncate(linkedinUrl, 120));
        }
        if (githubUrl != null && !githubUrl.isBlank()) {
            fieldConfidence.putIfAbsent("githubUrl", 0.99d);
            fieldEvidence.putIfAbsent("githubUrl", truncate(githubUrl, 120));
        }
        if (portfolioUrl != null && !portfolioUrl.isBlank()) {
            fieldConfidence.putIfAbsent("portfolioUrl", 0.98d);
            fieldEvidence.putIfAbsent("portfolioUrl", truncate(portfolioUrl, 120));
        }
        if (years != null && years > 0) {
            fieldConfidence.putIfAbsent("estimatedYearsExperience", 0.86d);
            fieldEvidence.putIfAbsent("estimatedYearsExperience", years + " years");
        }
        if (location != null && !location.isBlank()) {
            fieldConfidence.putIfAbsent("location", 0.80d);
            fieldEvidence.putIfAbsent("location", truncate(location, 120));
        }
        if (suggestedRoles != null && !suggestedRoles.isEmpty()) {
            fieldConfidence.putIfAbsent("suggestedRoles", 0.82d);
            fieldEvidence.putIfAbsent("suggestedRoles", String.join(", ", suggestedRoles.stream().limit(3).toList()));
        }
    }

    private static void addValidationWarnings(
            List<String> warnings,
            String displayName,
            String email,
            String phone,
            List<String> skills,
            List<String> significantSkills,
            List<String> suggestedRoles) {
        if (warnings == null) {
            return;
        }
        if (displayName == null || displayName.isBlank() || "Candidate".equalsIgnoreCase(displayName)) {
            warnings.add("Candidate name confidence is low; verify the extracted full name.");
        }
        if ((email == null || email.isBlank()) && (phone == null || phone.isBlank())) {
            warnings.add("No direct contact channel detected (email/phone missing).");
        }
        if (skills == null || skills.isEmpty()) {
            warnings.add("No known skills were extracted from this resume.");
        }
        if (significantSkills == null || significantSkills.isEmpty()) {
            warnings.add("No significant skills were ranked for this resume.");
        }
        if (suggestedRoles == null || suggestedRoles.isEmpty()) {
            warnings.add("No role alignment could be inferred from extracted content.");
        }
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

    private static SkillExtraction extractSkillExtraction(String text) {
        Map<String, Integer> counts = extractSkillCounts(text);
        List<String> ordered = counts.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry::getKey))
                .map(Map.Entry::getKey)
                .toList();

        List<String> significant = ordered.stream()
                .limit(SIGNIFICANT_SKILLS_LIMIT)
                .toList();

        return new SkillExtraction(ordered, significant);
    }

    private static Map<String, Integer> extractSkillCounts(String text) {
        String canonicalizedText = TechnicalRoleCatalog.canonicalizeSkillText(text);
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String skill : KNOWN_SKILLS) {
            int occurrences = TechnicalRoleCatalog.countSkillOccurrences(canonicalizedText, skill);
            if (occurrences > 0) {
                counts.put(skill, occurrences);
            }
        }
        return counts;
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
        for (TechnicalRoleCatalog.RoleDefinition definition : TechnicalRoleCatalog.roleDefinitions()) {
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

            if (TechnicalRoleCatalog.QA_ROLE_TITLE.equals(definition.title())
                    && skillHits < 2
                    && !containsAny(lowerText, TechnicalRoleCatalog.QA_TRACK_KEYWORDS)) {
                continue;
            }

            if (TechnicalRoleCatalog.TECH_LEAD_ROLE_TITLE.equals(definition.title())
                    && !containsAny(lowerText, TechnicalRoleCatalog.TECH_LEAD_TRACK_KEYWORDS)) {
                continue;
            }

            if (TechnicalRoleCatalog.ENGINEERING_MANAGER_ROLE_TITLE.equals(definition.title())
                    && !containsAny(lowerText, TechnicalRoleCatalog.MANAGER_TRACK_KEYWORDS)) {
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
            String portfolioUrl,
            String llmSummary) {
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
        if (llmSummary != null && !llmSummary.isBlank()) {
            sections.add("Profile summary: " + llmSummary + ".");
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

    private record RoleMatch(String title, int score, int skillHits, int keywordHits) {
    }
}
