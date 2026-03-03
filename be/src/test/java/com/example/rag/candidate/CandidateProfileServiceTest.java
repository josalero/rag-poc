package com.example.rag.candidate;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CandidateProfileServiceTest {

    @Test
    void indexResume_extractsLikelyFullNameFromTopLines(@TempDir Path tempDir) throws IOException {
        CandidateProfileService service = new CandidateProfileService();
        Path resume = tempDir.resolve("jose_adrian_aleman_rojas.pdf");
        Files.writeString(resume, "fake pdf bytes");

        String text = "SUMMARY\nJOSE ADRIAN ALEMAN ROJAS\nSenior Software Engineer\nExperience: 10 years in backend systems.";
        service.indexResume("jose_adrian_aleman_rojas.pdf", resume, text);

        CandidateProfile profile = service.getBySourceFilename("jose_adrian_aleman_rojas.pdf").orElseThrow();
        assertThat(profile.displayName()).isEqualTo("Jose Adrian Aleman Rojas");
    }

    @Test
    void indexResume_buildsSummaryStylePreview(@TempDir Path tempDir) throws IOException {
        CandidateProfileService service = new CandidateProfileService();
        Path resume = tempDir.resolve("alice-jones.pdf");
        Files.writeString(resume, "fake pdf bytes");

        String text = "Alice Jones\nLocation: Austin, TX\n"
                + "Software engineer with 8 years of experience building distributed services. "
                + "Led migration to Kubernetes and improved deployment reliability. "
                + "Strong Java, Spring, AWS, Docker, and Terraform background.";

        service.indexResume("alice-jones.pdf", resume, text);

        CandidateProfile profile = service.getBySourceFilename("alice-jones.pdf").orElseThrow();
        assertThat(profile.preview()).contains("Core skills:");
        assertThat(profile.preview()).contains("Highlights:");
        assertThat(profile.preview()).contains("Estimated experience");
    }

    @Test
    void indexResume_extractsContactChannels(@TempDir Path tempDir) throws IOException {
        CandidateProfileService service = new CandidateProfileService();
        Path resume = tempDir.resolve("contact-sample.pdf");
        Files.writeString(resume, "fake pdf bytes");

        String text = """
                Jane Doe
                jane.doe@example.com
                +1 (512) 555-0199
                linkedin.com/in/jane-doe
                github.com/janedoe
                janedoe.dev
                Senior engineer with 9 years of experience.
                """;

        service.indexResume("contact-sample.pdf", resume, text);

        CandidateProfile profile = service.getBySourceFilename("contact-sample.pdf").orElseThrow();
        assertThat(profile.email()).isEqualTo("jane.doe@example.com");
        assertThat(profile.phone()).isEqualTo("+15125550199");
        assertThat(profile.linkedinUrl()).isEqualTo("https://linkedin.com/in/jane-doe");
        assertThat(profile.githubUrl()).isEqualTo("https://github.com/janedoe");
        assertThat(profile.portfolioUrl()).isEqualTo("https://janedoe.dev");
    }

    @Test
    void indexResume_sanitizesPhoneNumberExtensions(@TempDir Path tempDir) throws IOException {
        CandidateProfileService service = new CandidateProfileService();
        Path resume = tempDir.resolve("phone-ext.pdf");
        Files.writeString(resume, "fake pdf bytes");

        String text = """
                Morgan Lee
                Contact: +1 (415) 555-1212 ext 44
                """;

        service.indexResume("phone-ext.pdf", resume, text);

        CandidateProfile profile = service.getBySourceFilename("phone-ext.pdf").orElseThrow();
        assertThat(profile.phone()).isEqualTo("+14155551212");
    }

    @Test
    void indexResume_extractsSignificantSkillsAndSuggestedRoles(@TempDir Path tempDir) throws IOException {
        CandidateProfileService service = new CandidateProfileService();
        Path resume = tempDir.resolve("backend-platform.pdf");
        Files.writeString(resume, "fake pdf bytes");

        String text = """
                Alex Rivera
                Senior backend engineer with 10 years of experience building Java and Spring microservices.
                Designed cloud APIs on AWS and led deployments with Docker and Kubernetes.
                Led a platform team and mentored engineers while improving CI/CD pipelines.
                """;

        service.indexResume("backend-platform.pdf", resume, text);

        CandidateProfile profile = service.getBySourceFilename("backend-platform.pdf").orElseThrow();
        assertThat(profile.significantSkills()).contains("JAVA", "SPRING", "AWS", "DOCKER", "KUBERNETES", "CI/CD");
        assertThat(profile.suggestedRoles()).contains("Senior Backend Engineer", "Senior DevOps / Platform Engineer");
        assertThat(profile.suggestedRoles()).hasSizeLessThanOrEqualTo(2);
    }

    @Test
    void indexResume_appliesSeniorityLevelsToSuggestedRoles(@TempDir Path tempDir) throws IOException {
        CandidateProfileService service = new CandidateProfileService();

        Path junior = tempDir.resolve("junior.pdf");
        Files.writeString(junior, "fake pdf bytes");
        service.indexResume("junior.pdf", junior, """
                Junior Developer
                Java and Spring experience.
                2 years building APIs.
                """);

        Path intermediate = tempDir.resolve("intermediate.pdf");
        Files.writeString(intermediate, "fake pdf bytes");
        service.indexResume("intermediate.pdf", intermediate, """
                Carlos Ruiz
                Software engineer with 4 years of experience in Java and Spring microservices.
                """);

        Path principal = tempDir.resolve("principal.pdf");
        Files.writeString(principal, "fake pdf bytes");
        service.indexResume("principal.pdf", principal, """
                Ana Gomez
                Principal engineer with 14 years of experience in backend architecture.
                Java, Spring, AWS.
                """);

        assertThat(service.getBySourceFilename("junior.pdf").orElseThrow().suggestedRoles())
                .contains("Junior Backend Engineer");
        assertThat(service.getBySourceFilename("intermediate.pdf").orElseThrow().suggestedRoles())
                .contains("Intermediate Backend Engineer");
        assertThat(service.getBySourceFilename("principal.pdf").orElseThrow().suggestedRoles())
                .contains("Principal Backend Engineer");
    }

    @Test
    void indexResume_doesNotTreatTechStackAsLocation(@TempDir Path tempDir) throws IOException {
        CandidateProfileService service = new CandidateProfileService();
        Path resume = tempDir.resolve("skills-location-noise.pdf");
        Files.writeString(resume, "fake pdf bytes");

        String text = """
                Maria Perez
                Senior engineer with 11 years of experience.
                Skills: PostgreSQL, MS SQL, Java, Spring, AWS.
                """;

        service.indexResume("skills-location-noise.pdf", resume, text);

        CandidateProfile profile = service.getBySourceFilename("skills-location-noise.pdf").orElseThrow();
        assertThat(profile.location()).isBlank();
    }

    @Test
    void indexResume_withDuplicateContentHash_mergesIntoSingleCandidate(@TempDir Path tempDir) throws IOException {
        CandidateProfileService service = new CandidateProfileService();
        Path first = tempDir.resolve("jane-doe-v1.pdf");
        Path second = tempDir.resolve("jane-doe-v2.pdf");
        Files.writeString(first, "fake pdf bytes");
        Files.writeString(second, "fake pdf bytes");

        String text = """
                Jane Doe
                jane.doe@example.com
                Senior engineer with cloud experience.
                """;

        service.indexResume("jane-doe-v1.pdf", first, text, "hash-123");
        assertThat(service.findDuplicateSource("jane-doe-v2.pdf", "hash-123")).contains("jane-doe-v1.pdf");

        service.indexResume("jane-doe-v2.pdf", second, text, "hash-123");

        CandidateProfile merged = service.getBySourceFilename("jane-doe-v2.pdf").orElseThrow();
        assertThat(merged.sourceFilename()).isEqualTo("jane-doe-v1.pdf");
        assertThat(merged.sourceFilenames()).containsExactlyInAnyOrder("jane-doe-v1.pdf", "jane-doe-v2.pdf");
    }
}
