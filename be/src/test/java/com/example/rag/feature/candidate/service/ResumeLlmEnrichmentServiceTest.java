package com.example.rag.feature.candidate.service;

import dev.langchain4j.model.chat.ChatModel;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ResumeLlmEnrichmentServiceTest {

    @Mock
    private ChatModel chatModel;

    private ResumeLlmEnrichmentService service;

    @BeforeEach
    void setUp() {
        service = new ResumeLlmEnrichmentService(chatModel);
        ReflectionTestUtils.setField(service, "enabled", true);
        ReflectionTestUtils.setField(service, "maxChars", 4000);
    }

    @Test
    void enrich_whenJsonWrapped_parsesAndSanitizes() {
        when(chatModel.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("""
                Here is the JSON:
                {
                  "displayName": "  JOSE ADRIAN ALEMAN ROJAS  ",
                  "summary": " Senior QA engineer with automation experience. ",
                  "skills": ["selenium", "cypress", "postman"],
                  "significantSkills": ["qa", "testing", "selenium"],
                  "suggestedRoles": ["Senior QA / Test Engineer", "Tech Lead"],
                  "estimatedYearsExperience": 9,
                  "location": "Austin, TX",
                  "fieldConfidence": {"displayName": 0.92, "skills": 1.4},
                  "fieldEvidence": {"displayName": "Name: Jose Adrian Aleman Rojas"}
                }
                """);

        Optional<ResumeLlmEnrichmentService.LlmProfileEnrichment> result = service.enrich("Resume text");

        assertThat(result).isPresent();
        ResumeLlmEnrichmentService.LlmProfileEnrichment enrichment = result.orElseThrow();
        assertThat(enrichment.displayName()).isEqualTo("JOSE ADRIAN ALEMAN ROJAS");
        assertThat(enrichment.skills()).contains("SELENIUM", "CYPRESS", "POSTMAN");
        assertThat(enrichment.significantSkills()).contains("QA", "TESTING", "SELENIUM");
        assertThat(enrichment.suggestedRoles()).contains("Senior QA / Test Engineer", "Tech Lead");
        assertThat(enrichment.estimatedYearsExperience()).isEqualTo(9);
        assertThat(enrichment.fieldConfidence().get("skills")).isEqualTo(1.0d);
    }

    @Test
    void enrich_whenResponseIsInvalid_returnsEmpty() {
        when(chatModel.chat(org.mockito.ArgumentMatchers.anyString())).thenReturn("No structured response");

        Optional<ResumeLlmEnrichmentService.LlmProfileEnrichment> result = service.enrich("Resume text");

        assertThat(result).isEmpty();
    }

    @Test
    void enrich_includesTechnicalRoleCatalogInPrompt() {
        when(chatModel.chat(anyString()))
                .thenReturn("""
                        {
                          "displayName": "Alex Rivera",
                          "summary": null,
                          "skills": [],
                          "significantSkills": [],
                          "suggestedRoles": ["Senior QA / Test Engineer"],
                          "estimatedYearsExperience": null,
                          "location": null,
                          "fieldConfidence": {},
                          "fieldEvidence": {}
                        }
                        """);

        Optional<ResumeLlmEnrichmentService.LlmProfileEnrichment> result = service.enrich("Resume text");

        assertThat(result).isPresent();
        assertThat(result.orElseThrow().suggestedRoles()).containsExactly("Senior QA / Test Engineer");
        verify(chatModel).chat(org.mockito.ArgumentMatchers.argThat((String prompt) ->
                prompt != null
                        && prompt.contains("Technical role catalog:")
                        && prompt.contains("Backend Engineer")
                        && prompt.contains("QA / Test Engineer")));
    }
}
