package com.aidevplatform.domain.model;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single deliverable produced by an agent (code file, doc, test, schema).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Deliverable implements Serializable {
    private static final long serialVersionUID = 1L;
    private String type;        // "code" | "doc" | "test" | "schema"
    private String name;
    private String filePath;
    private String description;
    private Integer lines;
}
