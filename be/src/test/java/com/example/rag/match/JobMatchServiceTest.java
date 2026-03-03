package com.example.rag.match;

import com.example.rag.candidate.CandidateProfile;
import com.example.rag.candidate.CandidateProfileService;
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
