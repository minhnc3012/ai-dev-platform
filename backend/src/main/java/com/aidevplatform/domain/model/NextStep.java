package com.aidevplatform.domain.model;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a recommended next action after an agent completes its work.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NextStep implements Serializable {
    private static final long serialVersionUID = 1L;
    private String action;
    private String agent;
    private String priority;   // "HIGH" | "MEDIUM" | "LOW"
}
