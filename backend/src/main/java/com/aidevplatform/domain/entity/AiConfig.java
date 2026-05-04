package com.aidevplatform.domain.entity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.aidevplatform.domain.enums.LlmInvocationMode;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

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
    @Default
    private String llmProvider = "openai_compatible";

    // Invocation mode: API | CLI | SDK
    // API mode is recommended for local Ollama server running at http://localhost:11434
    @Enumerated(EnumType.STRING)
    @Default
    private LlmInvocationMode invocationMode = LlmInvocationMode.API;

    // Used when invocationMode = API: base URL of the OpenAI-compatible endpoint
    // Examples:
    //   Local LM Studio  -> http://localhost:1234
    //   Local Ollama     -> http://localhost:11434
    //   Alibaba Cloud    -> https://dashscope.aliyuncs.com
    //   OpenAI           -> https://api.openai.com
    //   Anthropic        -> https://api.anthropic.com (SDK mode preferred)
    // Note: Do not include /v1 or /chat/completions in the URL
    @Default
    private String llmBaseUrl = "http://localhost:11434";

    // Model name exactly as the provider expects it
    // Examples: "qwen3.5:35b", "llama3.3:70b", "mistral-small-latest"
    @Default
    private String llmModelName = "qwen3.5:35b";

    // API key -- null for local models that do not require authentication (e.g., Ollama)
    private String llmApiKey;

    // Used when invocationMode = CLI: base executable only (flags are added dynamically)
    // Examples: "claude", "ollama run qwen3.5:35b", "/usr/local/bin/llama-cli -m /models/q.gguf"
    @Default
    private String llmCliCommand = "claude";

    // Optional: path to a --settings JSON file for Claude CLI mode
    // If set: claude --settings <path> --model <model> -p <prompt>
    // If empty: claude --model <model> -p <prompt>
    private String llmCliSettingsFile;

    // --- Generation parameters ---
    @Column(precision = 3, scale = 2)
    @Default
    private BigDecimal temperature = new BigDecimal("0.30");

    @Default
    private Integer maxTokensPerTask = 4096;

    // --- Agent behaviour ---
    @Default
    private String outputLanguage = "en"; // "en" or "vi" -- language used in agent reports
    /** @deprecated Replaced by WorkflowDefinition.defaultPauseForReview + WorkflowStage.pauseForReview. Kept for DB compatibility only. */
    @Deprecated
    @Default
    private Boolean approvalRequired = true;

    // --- Project context ---
    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    @Default
    private List<String> techStack = new ArrayList<>();

    @Column(columnDefinition = "text")
    private String codingStyleGuide;
}
