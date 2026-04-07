package com.aidevplatform.api.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Structured report returned by a Python agent when it completes its task.
 */
@Data
public class AgentReportDto {
    private String summary;
    private List<Map<String, Object>> deliverables;
    private List<Map<String, Object>> issuesFound;
    private List<Map<String, Object>> nextSteps;
    private List<Map<String, Object>> ownerDecisionsNeeded;
    private BigDecimal confidenceScore;
    private String confidenceReason;
    private Integer tokensUsed;
    private Integer durationSeconds;
}
