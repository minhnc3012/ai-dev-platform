package com.aidevplatform.domain.model;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents an issue or concern identified by an agent.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Issue  implements Serializable {
    private static final long serialVersionUID = 1L;
    private String severity;          // "BLOCKING" | "NON_BLOCKING"
    private String description;
    private String suggestedAction;
}
