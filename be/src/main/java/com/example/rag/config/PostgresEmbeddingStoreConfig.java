package com.example.rag.config;

import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.store.embedding.EmbeddingStore;
import dev.langchain4j.store.embedding.pgvector.PgVectorEmbeddingStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;

@Configuration
@ConditionalOnProperty(name = "app.store.type", havingValue = "postgres")
public class PostgresEmbeddingStoreConfig {

    @Value("${app.pgvector.table:}")
    private String configuredTableName;

    @Value("${app.embedding.provider:openrouter}")
    private String embeddingProvider;

    @Bean
    EmbeddingStore<TextSegment> embeddingStore(
            DataSource dataSource,
            EmbeddingModel embeddingModel) {
        String tableName = resolveTableName();
        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(dataSource)
                .table(tableName)
                .dimension(embeddingModel.dimension())
                .createTable(true)
                .build();
    }

    private String resolveTableName() {
        if (configuredTableName != null && !configuredTableName.isBlank()) {
            return configuredTableName.trim();
        }
        return "document_embeddings_" + normalizeProvider(embeddingProvider);
    }

    private static String normalizeProvider(String provider) {
        if (provider == null || provider.isBlank()) {
            return "openrouter";
        }
        return provider.trim().toLowerCase().replaceAll("[^a-z0-9]+", "_");
    }
}
