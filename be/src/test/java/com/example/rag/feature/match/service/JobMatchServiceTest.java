package com.example.rag.feature.match.service;

import com.example.rag.feature.match.model.*;

import com.example.rag.feature.candidate.model.CandidateProfile;
import com.example.rag.feature.candidate.service.CandidateProfileService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class JobMatchServiceTest {

    @Mock
    private CandidateProfileService candidateProfileService;

    private JobMatchService jobMatchService;

    @BeforeEach
    void setUp() {
        jobMatchService = new JobMatchService(candidateProfileService);
    }

    @Test
    void match_whenQaRoleIsRequested_prioritizesQaProfiles() {
        CandidateProfile qaCandidate = candidate(
                "qa-1",
                "Ana QA",
                List.of("Selenium", "Cypress", "Postman", "Jira"),
                List.of("Senior QA Engineer"),
                6,
                "Senior QA engineer focused on test automation, regression testing, and API testing."
        );
        CandidateProfile developerCandidate = candidate(
                "dev-1",
                "Bruno Dev",
                List.of("Java", "Spring", "React", "AWS"),
                List.of("Senior Backend Engineer"),
                8,
                "Backend engineer building microservices with Spring and PostgreSQL."
        );

        when(candidateProfileService.allCandidates()).thenReturn(List.of(developerCandidate, qaCandidate));

        JobMatchResponse response = jobMatchService.match(new JobMatchRequest(
                "Need a Senior QA Engineer for test automation, regression and API testing with Cypress and Postman.",
                List.of(),
                null,
                1,
                10
        ));

        assertThat(response.items()).hasSize(1);
        assertThat(response.total()).isEqualTo(1);
        assertThat(response.items().get(0).candidateId()).isEqualTo("qa-1");
        assertThat(response.items().get(0).overallScore()).isGreaterThanOrEqualTo(0.75d);
        assertThat(response.items().get(0).matchedSkills()).isNotEmpty();
        assertThat(response.inferredMustHaveSkills()).anyMatch(skill -> skill.equalsIgnoreCase("QA"));
    }

    @Test
    void match_whenMustHaveIsQaAlias_matchesFromProfileText() {
        CandidateProfile qaCandidate = candidate(
                "qa-2",
                "Mariana Tester",
                List.of("Jira"),
                List.of("Intermediate QA Engineer"),
                4,
                "Quality assurance specialist with strong test automation and API testing using Postman."
        );
        CandidateProfile developerCandidate = candidate(
                "dev-2",
                "Carlos Backend",
                List.of("Java", "Spring"),
                List.of("Intermediate Backend Engineer"),
                4,
                "Backend services and API development using Java and Spring."
        );

        when(candidateProfileService.allCandidates()).thenReturn(List.of(developerCandidate, qaCandidate));

        JobMatchResponse response = jobMatchService.match(new JobMatchRequest(
                "QA role",
                List.of("QA", "Test Automation"),
                null,
                1,
                10
        ));

        assertThat(response.items()).hasSize(1);
        assertThat(response.total()).isEqualTo(1);
        assertThat(response.items().get(0).candidateId()).isEqualTo("qa-2");
        assertThat(response.items().get(0).overallScore()).isGreaterThanOrEqualTo(0.75d);
        assertThat(response.items().get(0).matchedSkills())
                .anyMatch(skill -> skill.equalsIgnoreCase("QA"));
        assertThat(response.items().get(0).matchedSkills())
                .anyMatch(skill -> skill.equalsIgnoreCase("Test Automation"));
    }

    @Test
    void match_whenManualMinScoreIsHigh_filtersOutLowerMatches() {
        CandidateProfile qaCandidate = candidate(
                "qa-3",
                "Lucia QA",
                List.of("Selenium", "Cypress", "Postman", "Jira"),
                List.of("Senior QA Engineer"),
                6,
                "Senior QA engineer focused on test automation and API testing."
        );

        when(candidateProfileService.allCandidates()).thenReturn(List.of(qaCandidate));

        JobMatchResponse response = jobMatchService.match(new JobMatchRequest(
                "QA role with automation",
                List.of("QA", "Test Automation"),
                1.0d,
                1,
                10
        ));

        assertThat(response.items()).isEmpty();
        assertThat(response.total()).isZero();
    }

    @Test
    void match_whenFrontendRoleRequested_ranksFrontendProfileAboveBackendProfile() {
        CandidateProfile frontendCandidate = candidate(
                "fe-1",
                "Ana Frontend",
                List.of("React", "TypeScript", "JavaScript", "GraphQL"),
                List.of("Senior Frontend Engineer"),
                4,
                "Frontend engineer building React apps with TypeScript and modern UI architectures."
        );
        CandidateProfile backendCandidate = candidate(
                "be-1",
                "Luis Backend",
                List.of("Java", "Spring", "PostgreSQL", "Docker"),
                List.of("Senior Backend Engineer"),
                9,
                "Backend engineer building microservices in Java and Spring."
        );

        when(candidateProfileService.allCandidates()).thenReturn(List.of(backendCandidate, frontendCandidate));

        JobMatchResponse response = jobMatchService.match(new JobMatchRequest(
                "Need a Frontend Engineer with React and TypeScript experience.",
                List.of(),
                null,
                1,
                10
        ));

        assertThat(response.items()).isNotEmpty();
        assertThat(response.items().get(0).candidateId()).isEqualTo("fe-1");
    }

    @Test
    void match_whenLowerMinScoreProvided_includesPartialMatchesBelowDefaultThreshold() {
        CandidateProfile partialCandidate = candidate(
                "partial-1",
                "Mario Partial",
                List.of("React"),
                List.of("Backend Engineer"),
                5,
                "Engineer with some React exposure and backend API experience."
        );

        when(candidateProfileService.allCandidates()).thenReturn(List.of(partialCandidate));

        JobMatchResponse strictResponse = jobMatchService.match(new JobMatchRequest(
                "Need a Frontend Engineer with React and TypeScript experience.",
                List.of(),
                null,
                1,
                10
        ));
        JobMatchResponse relaxedResponse = jobMatchService.match(new JobMatchRequest(
                "Need a Frontend Engineer with React and TypeScript experience.",
                List.of(),
                0.20d,
                1,
                10
        ));

        assertThat(strictResponse.total()).isZero();
        assertThat(relaxedResponse.total()).isEqualTo(1);
        assertThat(relaxedResponse.items().get(0).candidateId()).isEqualTo("partial-1");
    }

    @Test
    void match_whenExplicitMustHaveProvided_usesOnlyExplicitMustHaveCoverage() {
        CandidateProfile reactCandidate = candidate(
                "react-1",
                "Rocio React",
                List.of("React"),
                List.of("Frontend Engineer"),
                4,
                "Frontend profile with React."
        );

        when(candidateProfileService.allCandidates()).thenReturn(List.of(reactCandidate));

        JobMatchResponse response = jobMatchService.match(new JobMatchRequest(
                "Need a Frontend Engineer with React and TypeScript experience.",
                List.of("React"),
                0.0d,
                1,
                10
        ));

        assertThat(response.items()).hasSize(1);
        assertThat(response.inferredMustHaveSkills()).containsExactly("REACT");
        assertThat(response.items().get(0).mustHaveCoverage()).isEqualTo(1.0d);
        assertThat(response.items().get(0).matchedSkills()).containsExactly("REACT");
        assertThat(response.items().get(0).missingMustHave()).isEmpty();
    }

    @Test
    void match_whenMustHaveAliasesProvided_normalizesToCanonicalSkills() {
        CandidateProfile candidate = candidate(
                "alias-1",
                "Diana Platform",
                List.of("JavaScript", "Kubernetes", "Spring Boot"),
                List.of("Backend Engineer"),
                6,
                "Backend engineer with JS services on K8S and SpringBoot APIs."
        );
        when(candidateProfileService.allCandidates()).thenReturn(List.of(candidate));

        JobMatchResponse response = jobMatchService.match(new JobMatchRequest(
                "Need engineer",
                List.of("JS", "K8S", "SpringBoot"),
                0.0d,
                1,
                10
        ));

        assertThat(response.inferredMustHaveSkills()).containsExactly("JAVASCRIPT", "KUBERNETES", "SPRING BOOT");
        assertThat(response.items()).hasSize(1);
        assertThat(response.items().get(0).matchedSkills()).containsExactly("JAVASCRIPT", "KUBERNETES", "SPRING BOOT");
    }

    @Test
    void match_whenNoKnownSkillsFound_infersFallbackMustHaveFromDescription() {
        when(candidateProfileService.allCandidates()).thenReturn(List.of());

        JobMatchResponse response = jobMatchService.match(new JobMatchRequest(
                "Need engineer with Golang Kafka and RabbitMQ experience.",
                List.of(),
                0.0d,
                1,
                10
        ));

        assertThat(response.inferredMustHaveSkills()).contains("GOLANG", "KAFKA");
    }

    private static CandidateProfile candidate(
            String id,
            String displayName,
            List<String> skills,
            List<String> suggestedRoles,
            Integer years,
            String preview) {
        Instant now = Instant.now();
        return new CandidateProfile(
                id,
                id + ".pdf",
                List.of(id + ".pdf"),
                displayName,
                "",
                "",
                "",
                "",
                "",
                skills,
                skills,
                suggestedRoles,
                years,
                "",
                1024L,
                now,
                now,
                preview,
                List.of()
        );
    }
}
