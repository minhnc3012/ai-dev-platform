package com.aidevplatform.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * Represents a decision point that requires the owner's input.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OwnerDecision implements Serializable {
    private static final long serialVersionUID = 1L;
    private String question;
    private List<String> options;
    private String impact;
}
