package com.aidevplatform.domain.entity;

import com.aidevplatform.domain.enums.LlmInvocationMode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "ai_configs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AiConfig extends BaseEntity {

    private static final long serialVersionUID = 1L;

    // --- LLM Provider ---
    // Supported values: "openai_compatible", "openai", "anthropic", "ollama_cli", "llama_cli", "custom"
    private String llmProvider = "openai_compatible";

    // Invocation mode: API | CLI | SDK
    // API mode is recommended for local Ollama server running at http://localhost:11434
    @Enumerated(EnumType.STRING)
    private LlmInvocationMode invocationMode = LlmInvocationMode.API;

    // Used when invocationMode = API: base URL of the OpenAI-compatible endpoint
    // Examples:
    //   Local LM Studio  -> http://localhost:1234
    //   Local Ollama     -> http://localhost:11434
    //   Alibaba Cloud    -> https://dashscope.aliyuncs.com
    //   OpenAI           -> https://api.openai.com
    //   Anthropic        -> https://api.anthropic.com (SDK mode preferred)
    // Note: Do not include /v1 or /chat/completions in the URL
    private String llmBaseUrl = "http://localhost:11434";

    // Model name exactly as the provider expects it
    // Examples: "qwen3.5:35b", "llama3.3:70b", "mistral-small-latest"
    private String llmModelName = "qwen3.5:35b";

    // API key -- null for local models that do not require authentication (e.g., Ollama)
    private String llmApiKey;

    // Used when invocationMode = CLI: the executable command
    // Examples:
    //   Ollama CLI  -> "ollama run qwen3.5:35b"
    //   llama.cpp   -> "/usr/local/bin/llama-cli -m /models/qwen3.5.gguf"
    private String llmCliCommand = "ollama run qwen3.5:35b";

    // --- Generation parameters ---
    @Column(precision = 3, scale = 2)
    private BigDecimal temperature = new BigDecimal("0.50");

    private Integer maxTokensPerTask = 4096;

    // --- Agent behaviour ---
    private String outputLanguage = "en"; // "en" or "vi" -- language used in agent reports
    private Boolean approvalRequired = true;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> activeAgents = new ArrayList<>(List.of("pm", "architect", "dev", "qa", "docs"));

    // --- Project context ---
    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private List<String> techStack = new ArrayList<>();

    @Column(columnDefinition = "text")
    private String codingStyleGuide;
}
