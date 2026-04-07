package com.aidevplatform.api.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * DTO used to broadcast agent events via SSE to the Vaadin UI.
 */
@Data
@Builder
public class AgentEventDto {
    private UUID eventId;
    private UUID runId;
    private String agentName;
    private String eventType;
    private String message;
    private String severity;
    private Map<String, Object> payload;
    private LocalDateTime timestamp;
}
