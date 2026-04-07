package com.aidevplatform.domain.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * Represents a parsed user story with acceptance criteria.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserStory implements Serializable {
    private static final long serialVersionUID = 1L;
    private String id;
    private String title;
    private String description;     // "As a <role>, I want <feature>, so that <benefit>"
    private List<String> acceptanceCriteria;
}
