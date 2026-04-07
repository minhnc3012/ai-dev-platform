package com.aidevplatform.domain.enums;

/**
 * Defines how the Python agent layer communicates with the LLM.
 *
 * API  — HTTP call to an OpenAI-compatible endpoint (LM Studio, Ollama server,
 *         Alibaba Cloud DashScope, OpenAI, etc.)
 * CLI  — Subprocess call to a local CLI tool (ollama run, llama-cli, etc.)
 * SDK  — Use a provider-specific Python SDK (anthropic, openai, etc.)
 */
public enum LlmInvocationMode {
    API,
    CLI,
    SDK
}
