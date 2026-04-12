package com.aidevplatform.service;

import com.aidevplatform.domain.entity.AgentReport;
import com.aidevplatform.domain.entity.AgentRun;
import com.aidevplatform.domain.entity.AgentSession;
import com.aidevplatform.repository.AgentReportRepository;
import com.aidevplatform.repository.AgentRunRepository;
import com.aidevplatform.repository.AgentSessionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Service for managing agent session memory compression.
 *
 * 3-Tier Memory Strategy:
 * Tier 1: Structured facts (AgentReport.deliverables, decisions) - persists in PostgreSQL
 * Tier 2: Session summary (LLM-compressed) - persists in AgentSession.summary
 * Tier 3: Raw recent messages - ephemeral in Redis (TTL 24h)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SessionCompressorService {

    private final AgentSessionRepository sessionRepository;
    private final AgentReportRepository agentReportRepository;
    private final AgentRunRepository agentRunRepository;
    private final RedisTemplate<String, String> redisTemplate;

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    private static final int RAW_MESSAGES_TTL_HOURS = 24;
    private static final int MAX_RAW_MESSAGES = 20;

    /**
     * Compresses a completed session from AgentReport (structured facts).
     * This extracts structured facts and creates summary from the report.
     */
    @Transactional
    public void compressSessionFromReport(UUID runId) {
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));

        AgentReport report = agentReportRepository.findByRunId(run.getId()).orElse(null);
        if (report == null) {
            log.warn("No report found for run {}, cannot compress session", runId);
            return;
        }

        // Extract structured facts
        ObjectNode facts = objectMapper.createObjectNode();
        if (report.getDeliverables() != null && !report.getDeliverables().isEmpty()) {
            facts.set("deliverables", objectMapper.valueToTree(report.getDeliverables()));
        }
        if (report.getIssuesFound() != null && !report.getIssuesFound().isEmpty()) {
            facts.set("issues", objectMapper.valueToTree(report.getIssuesFound()));
        }
        if (report.getOwnerDecisionsNeeded() != null && !report.getOwnerDecisionsNeeded().isEmpty()) {
            facts.set("decisions", objectMapper.valueToTree(report.getOwnerDecisionsNeeded()));
        }
        if (report.getConfidenceScore() != null) {
            facts.put("confidenceScore", report.getConfidenceScore());
        }
        if (report.getConfidenceReason() != null) {
            facts.put("confidenceReason", report.getConfidenceReason());
        }

        // Create session record with report summary and extracted facts
        AgentSession session = AgentSession.builder()
                .id(runId)
                .module(run.getModule())
                .run(run)
                .summary(report.getSummary())
                .reasoningSummary(report.getConfidenceReason() != null ?
                        "Confidence: " + report.getConfidenceReason() : "No confidence reasoning")
                .metadata(facts)
                .lastMessageAt(LocalDateTime.now())
                .messageCount(0)
                .status(AgentSession.SessionStatus.COMPRESSED)
                .build();

        sessionRepository.save(session);
        log.info("Session compressed from report for run {}", runId);
    }

    /**
     * Compresses a completed session by:
     * 1. Creating session summary from agent's chat history
     * 2. Extracting structured facts into AgentSession.metadata
     * 3. Preserving raw recent messages in Redis with TTL
     */
    @Transactional
    public AgentSession compressSession(UUID runId, String chatHistory) {
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));

        // 1. Generate LLM-compressed summary (currently using report summary as placeholder)
        String sessionSummary = generateSessionSummary(run);
        String reasoningSummary = "Design decisions will be captured when implemented.";

        // 2. Extract structured facts
        ObjectNode facts = extractStructuredFacts(run);

        // 3. Save raw recent messages to Redis (ephemeral)
        String recentMessages = extractRecentMessages(chatHistory, MAX_RAW_MESSAGES);
        String redisKey = "session:raw:" + runId;
        try {
            redisTemplate.opsForValue().set(redisKey, recentMessages,
                    RAW_MESSAGES_TTL_HOURS, java.util.concurrent.TimeUnit.HOURS);
            log.info("Saved {} recent messages to Redis for run {}", MAX_RAW_MESSAGES, runId);
        } catch (Exception e) {
            log.warn("Failed to save raw messages to Redis for run {}", runId, e);
        }

        // 4. Persist session record
        AgentSession session = AgentSession.builder()
                .id(runId)
                .module(run.getModule())
                .run(run)
                .summary(sessionSummary)
                .reasoningSummary(reasoningSummary)
                .metadata(facts)
                .lastMessageAt(LocalDateTime.now())
                .messageCount(extractMessageCount(chatHistory))
                .status(AgentSession.SessionStatus.COMPRESSED)
                .build();

        AgentSession saved = sessionRepository.save(session);
        log.info("Compressed session for run {} - summary length: {} chars",
                runId, saved.getSummary().length());

        return saved;
    }

    /**
     * Generates a concise summary of what the agent accomplished.
     */
    private String generateSessionSummary(AgentRun run) {
        // TODO: Call LLM to summarize the chat history
        // For now, use existing report summary as a placeholder
        return agentReportRepository.findByRunId(run.getId())
                .map(report -> report.getSummary() != null ? report.getSummary() : "Session completed.")
                .orElse("Session completed with no report.");
    }

    /**
     * Extracts structured facts that should never be forgotten.
     */
    private ObjectNode extractStructuredFacts(AgentRun run) {
        return agentReportRepository.findByRunId(run.getId())
                .map(report -> {
                    ObjectNode facts = objectMapper.createObjectNode();
                    if (report.getDeliverables() != null && !report.getDeliverables().isEmpty()) {
                        facts.set("deliverables", objectMapper.valueToTree(report.getDeliverables()));
                    }
                    if (report.getIssuesFound() != null && !report.getIssuesFound().isEmpty()) {
                        facts.set("issues", objectMapper.valueToTree(report.getIssuesFound()));
                    }
                    if (report.getOwnerDecisionsNeeded() != null && !report.getOwnerDecisionsNeeded().isEmpty()) {
                        facts.set("decisions", objectMapper.valueToTree(report.getOwnerDecisionsNeeded()));
                    }
                    if (report.getConfidenceScore() != null) {
                        facts.put("confidenceScore", report.getConfidenceScore());
                    }
                    if (report.getConfidenceReason() != null) {
                        facts.put("confidenceReason", report.getConfidenceReason());
                    }
                    return facts;
                })
                .orElse(objectMapper.createObjectNode());
    }

    /**
     * Extracts last N messages from chat history.
     */
    private String extractRecentMessages(String chatHistory, int maxMessages) {
        if (chatHistory == null || chatHistory.isBlank()) {
            return "";
        }
        String[] lines = chatHistory.split("\n");
        int take = Math.min(maxMessages, lines.length);
        int start = Math.max(0, lines.length - take);

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < lines.length; i++) {
            sb.append(lines[i]).append("\n");
        }
        return sb.toString();
    }

    /**
     * Counts message turns in chat history.
     */
    private int extractMessageCount(String chatHistory) {
        if (chatHistory == null || chatHistory.isBlank()) {
            return 0;
        }
        int count = 0;
        String[] lines = chatHistory.split("\n");
        for (String line : lines) {
            if (line.trim().startsWith("Assistant:") ||
                line.trim().startsWith("assistant:") ||
                line.trim().startsWith("User:") ||
                line.trim().startsWith("user:")) {
                count++;
            }
        }
        return count;
    }

    /**
     * Retrieves session context for resumption.
     */
    @Transactional(readOnly = true)
    public ResumptionContext getSessionContext(UUID runId) {
        AgentSession session = sessionRepository.findByRunId(runId).orElse(null);

        ObjectNode structuredFacts = null;
        String sessionSummary = null;
        String reasoningSummary = null;

        if (session != null) {
            structuredFacts = session.getMetadata();
            sessionSummary = session.getSummary();
            reasoningSummary = session.getReasoningSummary();
        } else {
            AgentReport report = agentReportRepository.findByRunId(runId).orElse(null);
            if (report != null) {
                ObjectNode node = objectMapper.createObjectNode();
                node.set("deliverables", objectMapper.valueToTree(report.getDeliverables()));
                node.set("issues", objectMapper.valueToTree(report.getIssuesFound()));
                if (report.getConfidenceScore() != null) {
                    node.put("confidenceScore", report.getConfidenceScore());
                }
                structuredFacts = node;
                sessionSummary = report.getSummary();
            }
        }

        String recentMessages = redisTemplate.opsForValue().get("session:raw:" + runId);

        return new ResumptionContext(structuredFacts, sessionSummary, reasoningSummary, recentMessages);
    }

    /**
     * Represents the context to inject into an agent's system prompt.
     */
    public record ResumptionContext(
            ObjectNode structuredFacts,
            String sessionSummary,
            String reasoningSummary,
            String recentRawMessages
    ) {
        /**
         * Formats the context as a system prompt string.
         */
        public String toSystemPrompt(String agentName, ObjectMapper objectMapper) {
            StringBuilder sb = new StringBuilder();
            sb.append("## Agent: ").append(agentName).append("\n\n");

            // Tier 1: Structured facts
            sb.append("## Completed Work (Structured Facts)\n");
            if (structuredFacts != null && !structuredFacts.isEmpty()) {
                sb.append(structuredFacts.toPrettyString()).append("\n\n");
            } else {
                sb.append("(No structured facts yet)\n\n");
            }

            // Tier 2: Session summary
            sb.append("## Session Summary\n");
            if (sessionSummary != null && !sessionSummary.isBlank()) {
                sb.append(sessionSummary).append("\n\n");
            } else {
                sb.append("(No session summary available)\n\n");
            }

            // Tier 2b: Reasoning summary
            if (reasoningSummary != null && !reasoningSummary.isBlank()) {
                sb.append("## Design Decisions & Trade-offs\n");
                sb.append(reasoningSummary).append("\n\n");
            }

            // Tier 3: Recent raw messages
            sb.append("## Recent Activity\n");
            if (recentRawMessages != null && !recentRawMessages.isBlank()) {
                sb.append("```plaintext\n");
                sb.append(recentRawMessages);
                sb.append("\n```\n");
            } else {
                sb.append("(No recent activity - this is expected for a new agent or fresh start)\n");
            }

            return sb.toString();
        }
    }
}
