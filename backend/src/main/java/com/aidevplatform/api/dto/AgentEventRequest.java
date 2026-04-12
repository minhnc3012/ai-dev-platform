package com.aidevplatform.api.dto;

import lombok.Data;

import com.fasterxml.jackson.databind.node.ObjectNode;
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
    private ObjectNode payload;
    private String timestamp;
}
