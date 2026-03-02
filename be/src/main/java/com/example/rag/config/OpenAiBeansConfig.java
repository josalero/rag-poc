package com.example.rag.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiEmbeddingModel;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * Provides LangChain4j OpenAI/OpenRouter beans without using the Spring Boot starter
 * (avoids RestClientAutoConfiguration class path issue on Spring Boot 4).
 */
@Configuration
public class OpenAiBeansConfig {

    @Bean
    ChatModel chatModel(
            @Value("${langchain4j.open-ai.chat-model.api-key:}") String apiKey,
            @Value("${langchain4j.open-ai.chat-model.base-url:https://openrouter.ai/api/v1}") String baseUrl,
            @Value("${langchain4j.open-ai.chat-model.model-name:openai/gpt-4o-mini}") String modelName) {
        String key = apiKey != null ? apiKey.trim() : "";
        if (key.isEmpty()) {
            throw new IllegalStateException(
                    "OpenRouter API key is required for chat model. Set OPENROUTER_API_KEY or langchain4j.open-ai.chat-model.api-key.");
        }
        return OpenAiChatModel.builder()
                .apiKey(key)
                .baseUrl(baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : "https://openrouter.ai/api/v1")
                .modelName(modelName != null && !modelName.isBlank() ? modelName.trim() : "openai/gpt-4o-mini")
                .timeout(Duration.ofSeconds(120))
                .build();
    }

    @Bean
    EmbeddingModel embeddingModel(
            @Value("${langchain4j.open-ai.embedding-model.api-key:}") String apiKey,
            @Value("${langchain4j.open-ai.embedding-model.base-url:}") String baseUrl,
            @Value("${langchain4j.open-ai.embedding-model.model-name:text-embedding-3-small}") String modelName) {
        String key = apiKey != null ? apiKey.trim() : "";
        if (key.isEmpty()) {
            throw new IllegalStateException(
                    "API key is required for embedding model. Set OPENROUTER_API_KEY (or OPENAI_API_KEY) and langchain4j.open-ai.embedding-model.api-key.");
        }
        var builder = OpenAiEmbeddingModel.builder()
                .apiKey(key)
                .modelName(modelName != null && !modelName.isBlank() ? modelName.trim() : "text-embedding-3-small");
        if (baseUrl != null && !baseUrl.isBlank()) {
            builder.baseUrl(baseUrl.trim());
        }
        return builder.build();
    }
}
