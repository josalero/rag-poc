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

    @Value("${app.pgvector.table:document_embeddings}")
    private String tableName;

    @Bean
    EmbeddingStore<TextSegment> embeddingStore(
            DataSource dataSource,
            EmbeddingModel embeddingModel) {
        return PgVectorEmbeddingStore.datasourceBuilder()
                .datasource(dataSource)
                .table(tableName)
                .dimension(embeddingModel.dimension())
                .createTable(true)
                .build();
    }
}
