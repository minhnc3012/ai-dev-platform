package com.aidevplatform.api.dto;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;

import java.util.UUID;

/**
 * Completion callback from a Python agent.
 * Contains the run ID, structured report, and token usage.
 */
@Data
public class AgentCallbackDto {
    private UUID runId;
    private ObjectNode report;
    private Integer tokensUsed;
}
