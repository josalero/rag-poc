package com.example.rag.feature.role.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TechnicalRoleCatalogTest {

    @Test
    void normalizeRoleTitle_preservesKnownRoleWithSeniorityPrefix() {
        String normalized = TechnicalRoleCatalog.normalizeRoleTitle("senior qa / test engineer");
        assertThat(normalized).isEqualTo("Senior QA / Test Engineer");
    }

    @Test
    void llmRoleCatalogPrompt_includesCanonicalRoles() {
        String prompt = TechnicalRoleCatalog.llmRoleCatalogPrompt();
        assertThat(prompt).contains("Backend Engineer");
        assertThat(prompt).contains("QA / Test Engineer");
        assertThat(prompt).contains("Engineering Manager");
    }

    @Test
    void canonicalSkills_includeExpandedProductionSkills() {
        assertThat(TechnicalRoleCatalog.canonicalSkills())
                .contains("SPRING BOOT", "JPA/HIBERNATE", "KAFKA", "RABBITMQ", "LANGCHAIN4J", "OPENAI", "PGVECTOR");
    }

    @Test
    void normalizeSkill_mapsCommonAliasesToCanonicalValues() {
        assertThat(TechnicalRoleCatalog.normalizeSkill("js")).isEqualTo("JAVASCRIPT");
        assertThat(TechnicalRoleCatalog.normalizeSkill("k8s")).isEqualTo("KUBERNETES");
        assertThat(TechnicalRoleCatalog.normalizeSkill("SpringBoot")).isEqualTo("SPRING BOOT");
        assertThat(TechnicalRoleCatalog.normalizeSkill("pg vector")).isEqualTo("PGVECTOR");
        assertThat(TechnicalRoleCatalog.normalizeSkill("ci cd")).isEqualTo("CI/CD");
    }
}
