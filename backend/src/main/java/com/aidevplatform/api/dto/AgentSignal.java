package com.aidevplatform.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Represents a signal to be published to Redis Streams for agent pipeline control.
 *
 * Signal types:
 * - APPROVE: Agent run approved by owner, trigger next agent
 * - COMPLETE: Agent completed (with or without approval), process for next step
 * - REJECT: Agent run rejected, create retry and trigger next
 * - RESUME: Pipeline resume after backend restart
 *
 * Each signal is persisted in Redis Stream until consumed and acknowledged.
 */
@Data
@Builder
public class AgentSignal {

    /** Unique signal ID for deduplication and tracking */
    private UUID signalId;

    /** Type of signal */
    private SignalType signalType;

    /** The AgentRun ID this signal relates to */
    private UUID runId;

    /** The Module ID this signal relates to */
    private UUID moduleId;

    /** The agent name this signal relates to */
    private String agentName;

    /** Additional data/context for the signal */
    private Map<String, Object> data;

    /** Timestamp when signal was created */
    private LocalDateTime timestamp;

    /** Signal type enum */
    public enum SignalType {
        APPROVE,      // Owner approved agent run
        REJECT,       // Owner rejected agent run
        COMPLETE,     // Agent completed (auto-trigger case)
        RESUME        // Pipeline resume on restart
    }

    /**
     * Create an APPROVE signal for a given agent run.
     */
    public static AgentSignal createApproveSignal(AgentRunInfo runInfo) {
        return AgentSignal.builder()
                .signalId(UUID.randomUUID())
                .signalType(SignalType.APPROVE)
                .runId(runInfo.getRunId())
                .moduleId(runInfo.getModuleId())
                .agentName(runInfo.getAgentName())
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Create a REJECT signal with reason for a given agent run.
     */
    public static AgentSignal createRejectSignal(AgentRunInfo runInfo, String reason) {
        return AgentSignal.builder()
                .signalId(UUID.randomUUID())
                .signalType(SignalType.REJECT)
                .runId(runInfo.getRunId())
                .moduleId(runInfo.getModuleId())
                .agentName(runInfo.getAgentName())
                .data(Map.of("reason", reason))
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Create a COMPLETE signal (for auto-trigger case).
     */
    public static AgentSignal createCompleteSignal(AgentRunInfo runInfo) {
        return AgentSignal.builder()
                .signalId(UUID.randomUUID())
                .signalType(SignalType.COMPLETE)
                .runId(runInfo.getRunId())
                .moduleId(runInfo.getModuleId())
                .agentName(runInfo.getAgentName())
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Create a RESUME signal for pipeline recovery.
     */
    public static AgentSignal createResumeSignal(UUID moduleId, String reason) {
        return AgentSignal.builder()
                .signalId(UUID.randomUUID())
                .signalType(SignalType.RESUME)
                .moduleId(moduleId)
                .data(Map.of("reason", reason))
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Simple record to hold agent run information.
     */
    @lombok.Value
    public static class AgentRunInfo {
        UUID runId;
        UUID moduleId;
        String agentName;
    }
}
