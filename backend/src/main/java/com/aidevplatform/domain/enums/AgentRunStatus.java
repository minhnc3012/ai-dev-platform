package com.aidevplatform.domain.enums;

public enum AgentRunStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
    AWAITING_APPROVAL,
    APPROVED,
    REJECTED,
    TERMINATED // Manually stopped by user or system
}
