package com.aidevplatform.api.dto;

import lombok.Data;

import java.util.UUID;

/**
 * Completion callback from a Python agent.
 * Contains the run ID, structured report, and token usage.
 */
@Data
public class AgentCallbackDto {
    private UUID runId;
    private AgentReportDto report;
    private Integer tokensUsed;
}
