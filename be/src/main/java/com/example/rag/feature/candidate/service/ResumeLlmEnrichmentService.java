package com.example.rag.feature.candidate.service;

import com.example.rag.feature.candidate.model.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.example.rag.feature.role.domain.TechnicalRoleCatalog;
import dev.langchain4j.model.chat.ChatModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
public class ResumeLlmEnrichmentService {

    private static final Logger log = LoggerFactory.getLogger(ResumeLlmEnrichmentService.class);
    private static final int DEFAULT_MAX_CHARS = 8000;
    private static final String PROMPT_TEMPLATE = """
            Extract candidate profile fields from this resume text.
            Return STRICT JSON only (no markdown), exactly with keys:
            {
              "displayName": string|null,
              "summary": string|null,
              "skills": string[],
              "significantSkills": string[],
              "suggestedRoles": string[],
              "estimatedYearsExperience": number|null,
              "location": string|null,
              "fieldConfidence": {
                "displayName": number,
                "summary": number,
                "skills": number,
                "significantSkills": number,
                "suggestedRoles": number,
                "estimatedYearsExperience": number,
                "location": number
              },
              "fieldEvidence": {
                "displayName": string,
                "summary": string,
                "skills": string,
                "significantSkills": string,
                "suggestedRoles": string,
                "estimatedYearsExperience": string,
                "location": string
              }
            }

            Rules:
            - Use ONLY resume evidence; never invent facts.
            - skills: up to 20 uppercase values.
            - significantSkills: top 8 uppercase values.
            - suggestedRoles: top 3 concise role titles from the technical role catalog below.
            - estimatedYearsExperience: integer if explicit/strong evidence, else null.
            - If unknown, return null or [] as applicable.

            Technical role catalog:
            %s

            Resume text:
            %s
            """;

    private final ChatModel chatModel;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${app.ingest.llm-enrichment.enabled:true}")
    private boolean enabled;

    @Value("${app.ingest.llm-enrichment.max-chars:8000}")
    private int maxChars;

    public ResumeLlmEnrichmentService(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Optional<LlmProfileEnrichment> enrich(String normalizedText) {
        if (!enabled || normalizedText == null || normalizedText.isBlank()) {
            return Optional.empty();
        }
        int effectiveMaxChars = Math.max(1000, maxChars > 0 ? maxChars : DEFAULT_MAX_CHARS);
        String excerpt = normalizedText.length() > effectiveMaxChars
                ? normalizedText.substring(0, effectiveMaxChars)
                : normalizedText;
        String prompt = String.format(PROMPT_TEMPLATE, TechnicalRoleCatalog.llmRoleCatalogPrompt(), excerpt);
        try {
            String raw = chatModel.chat(prompt);
            String json = extractJsonObject(raw);
            LlmProfileEnrichment parsed = objectMapper.readValue(json, LlmProfileEnrichment.class);
            return Optional.of(sanitize(parsed));
        } catch (Exception e) {
            log.warn("LLM enrichment failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private static String extractJsonObject(String value) {
        if (value == null) {
            throw new IllegalArgumentException("LLM response is null");
        }
        int start = value.indexOf('{');
        int end = value.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("LLM response does not contain a JSON object");
        }
        return value.substring(start, end + 1);
    }

    private static LlmProfileEnrichment sanitize(LlmProfileEnrichment input) {
        if (input == null) {
            return new LlmProfileEnrichment(null, null, List.of(), List.of(), List.of(), null, null, Map.of(), Map.of());
        }
        String displayName = cleanText(input.displayName(), 80);
        String summary = cleanText(input.summary(), 320);
        List<String> skills = normalizeSkills(input.skills(), 20);
        List<String> significantSkills = normalizeSkills(input.significantSkills(), 8);
        List<String> suggestedRoles = normalizeTitles(input.suggestedRoles(), 3);
        Integer years = input.estimatedYearsExperience();
        if (years != null && (years < 0 || years > 60)) {
            years = null;
        }
        String location = cleanText(input.location(), 120);
        Map<String, Double> confidence = sanitizeConfidence(input.fieldConfidence());
        Map<String, String> evidence = sanitizeEvidence(input.fieldEvidence());
        return new LlmProfileEnrichment(
                displayName,
                summary,
                skills,
                significantSkills,
                suggestedRoles,
                years,
                location,
                confidence,
                evidence
        );
    }

    private static String cleanText(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        String compact = value.replaceAll("\\s+", " ").trim();
        if (compact.isBlank()) {
            return null;
        }
        if (compact.length() > maxChars) {
            return compact.substring(0, maxChars).trim();
        }
        return compact;
    }

    private static List<String> normalizeSkills(List<String> values, int limit) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String compact = cleanText(value, 64);
                if (compact == null) {
                    continue;
                }
                String canonical = TechnicalRoleCatalog.normalizeSkill(compact);
                if (canonical.isBlank()) {
                    continue;
                }
                normalized.add(canonical);
                if (normalized.size() >= limit) {
                    break;
                }
            }
        }
        return List.copyOf(normalized);
    }

    private static List<String> normalizeTitles(List<String> values, int limit) {
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String compact = cleanText(value, 80);
                if (compact == null) {
                    continue;
                }
                String canonical = TechnicalRoleCatalog.normalizeRoleTitle(compact);
                if (canonical.isBlank()) {
                    continue;
                }
                normalized.add(canonical);
                if (normalized.size() >= limit) {
                    break;
                }
            }
        }
        return List.copyOf(normalized);
    }

    private static Map<String, Double> sanitizeConfidence(Map<String, Double> values) {
        Map<String, Double> sanitized = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((key, value) -> {
                if (key == null || key.isBlank() || value == null) {
                    return;
                }
                sanitized.put(key.trim(), Math.max(0.0d, Math.min(1.0d, value)));
            });
        }
        return Map.copyOf(sanitized);
    }

    private static Map<String, String> sanitizeEvidence(Map<String, String> values) {
        Map<String, String> sanitized = new LinkedHashMap<>();
        if (values != null) {
            values.forEach((key, value) -> {
                if (key == null || key.isBlank() || value == null || value.isBlank()) {
                    return;
                }
                sanitized.put(key.trim(), cleanText(value, 220));
            });
        }
        sanitized.values().removeIf(v -> v == null || v.isBlank());
        return Map.copyOf(sanitized);
    }

    public record LlmProfileEnrichment(
            String displayName,
            String summary,
            List<String> skills,
            List<String> significantSkills,
            List<String> suggestedRoles,
            Integer estimatedYearsExperience,
            String location,
            Map<String, Double> fieldConfidence,
            Map<String, String> fieldEvidence) {
    }
}
