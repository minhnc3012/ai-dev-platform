package com.aidevplatform.api.dto;

import lombok.Data;

import java.util.Map;
import java.util.UUID;

/**
 * Request payload for the /api/internal/agent/event endpoint.
 * Sent by Python agents to report real-time progress.
 */
@Data
public class AgentEventRequest {
    private UUID runId;
    private String eventType;
    private String message;
    private Map<String, Object> payload;
    private String timestamp;
}
