package com.aidevplatform.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Service for LLM-based chat history summarization.
 *
 * This service calls an LLM to compress long chat histories into concise summaries
 * that capture:
 * - What was accomplished
 * - Key decisions made and why
 * - Important context for next agent
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatSummarizationService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    @Value("${ai.summarization.model:gpt-4o}")
    private String summarizationModel;

    @Value("${ai.summarization.api-key:}")
    private String summarizationApiKey;

    @Value("${ai.summarization.base-url:}")
    private String summarizationBaseUrl;

    @Value("${ai.summarization.system-prompt:You are an expert AI assistant that summarizes chat conversations. Provide a concise summary of what was accomplished and any important decisions made.}")
    private String systemPrompt;

    /**
     * Summarizes a chat history, extracting:
     * - Summary of work accomplished
     * - Key design decisions and reasoning
     */
    public ChatSummary summarizeChat(String chatHistory, String agentName) {
        if (chatHistory == null || chatHistory.isBlank()) {
            return new ChatSummary("", "", LocalDateTime.now());
        }

        // Count tokens (rough estimate: 1 token ~= 4 chars for English text)
        int tokenCount = chatHistory.length() / 4;
        if (tokenCount > 10000) {
            log.warn("Chat history has {} tokens, may exceed model limits. Truncating.", tokenCount);
            chatHistory = chatHistory.substring(0, 40000); // Rough 10k tokens
            tokenCount = 10000;
        }

        // Prepare messages for LLM
        List<Map<String, String>> messages = buildSummarizationMessages(chatHistory);

        try {
            String response = callSummarizationLLM(messages);

            // Parse response
            ChatSummary summary = parseSummarizationResponse(response, agentName);
            log.info("Summarized {} tokens into: summary ({} chars), reasoning ({} chars)",
                    tokenCount,
                    summary.summary().length(),
                    summary.reasoning().length());
            return summary;

        } catch (Exception e) {
            log.error("Failed to summarize chat history for agent {}: {}", agentName, e.getMessage());
            // Fallback: return basic summary from AgentReport
            return new ChatSummary("LLM summarization failed", "No reasoning available", LocalDateTime.now());
        }
    }

    /**
     * Extracts decision reasoning from chat history.
     * Looks for patterns like "decision:", "choice:", "reason for", "trade-off".
     */
    public String extractDecisionReasoning(String chatHistory) {
        if (chatHistory == null || chatHistory.isBlank()) {
            return "";
        }

        List<String> decisionPatterns = List.of(
                "decision:",
                "choice:",
                "reason for",
                "trade-off",
                "why we",
                "because",
                "to ensure",
                "to avoid"
        );

        StringBuilder reasoning = new StringBuilder();
        String[] lines = chatHistory.split("\n");

        for (String line : lines) {
            line = line.trim();
            for (String pattern : decisionPatterns) {
                if (line.toLowerCase().contains(pattern.toLowerCase())) {
                    reasoning.append(line).append("\n");
                    break;
                }
            }
        }

        return reasoning.toString().trim();
    }

    private List<Map<String, String>> buildSummarizationMessages(String chatHistory) {
        List<Map<String, String>> messages = new ArrayList<>();

        messages.add(Map.of(
                "role", "system",
                "content", systemPrompt + " Focus on: 1) What was accomplished, 2) Key decisions and why," +
                        " 3) Important context for next agent. Be concise but comprehensive."
        ));

        messages.add(Map.of(
                "role", "user",
                "content", "Summarize this chat conversation:\n\n" + chatHistory
        ));

        return messages;
    }

    private String callSummarizationLLM(List<Map<String, String>> messages) throws Exception {
        String url = summarizationBaseUrl + "/chat/completions";
        if (summarizationBaseUrl.isBlank()) {
            url = "https://api.openai.com/v1/chat/completions";
        }

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (!summarizationApiKey.isBlank()) {
            headers.set("Authorization", "Bearer " + summarizationApiKey);
        }

        Map<String, Object> body = new HashMap<>();
        body.put("model", summarizationModel);
        body.put("messages", messages);
        body.put("temperature", 0.3); // Low temperature for consistent summaries

        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);
        ResponseEntity<Map> response = restTemplate.postForEntity(url, request, Map.class);

        if (response.getStatusCode().is2xxSuccessful()) {
            Map<String, Object> choices = (Map<String, Object>) response.getBody().get("choices");
            List<Map<String, Object>> choicesList = (List<Map<String, Object>>) choices;
            Map<String, Object> choice = choicesList.get(0);
            Map<String, Object> message = (Map<String, Object>) choice.get("message");
            Object content = message.get("content");
            return content != null ? content.toString() : "";
        }

        throw new Exception("LLM API error: " + response.getStatusCode());
    }

    private ChatSummary parseSummarizationResponse(String response, String agentName) {
        // Response format from LLM:
        // Summary: [text]
        // Reasoning: [text]
        // End

        String summary;
        String reasoning;

        String[] lines = response.split("\n");
        StringBuilder summaryBuilder = new StringBuilder();
        StringBuilder reasoningBuilder = new StringBuilder();
        boolean inReasoning = false;

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("Summary:") || line.startsWith("## Summary:")) {
                summaryBuilder.append(line.substring("Summary:".length()).trim()).append("\n");
                continue;
            }
            if (line.startsWith("Reasoning:") || line.startsWith("## Reasoning:") ||
                line.startsWith("## Design Decisions:")) {
                inReasoning = true;
                reasoningBuilder.append(line.substring("Reasoning:".length()).trim()).append("\n");
                continue;
            }
            if (inReasoning) {
                reasoningBuilder.append(line).append("\n");
            } else if (!line.isBlank()) {
                summaryBuilder.append(line).append("\n");
            }
        }

        return new ChatSummary(
                summaryBuilder.toString().trim(),
                reasoningBuilder.toString().trim(),
                LocalDateTime.now()
        );
    }

    /**
     * Summarization result.
     */
    public record ChatSummary(
            String summary,
            String reasoning,
            LocalDateTime timestamp
    ) {
        /**
         * Formats summary for injection into agent context.
         */
        public String toContextString(String agentName) {
            return """
                    ## Summary of Previous Work
                    %s

                    ## Key Decisions & Reasoning
                    %s
                    """.formatted(summary, reasoning);
        }
    }
}
