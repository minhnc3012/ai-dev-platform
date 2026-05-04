package com.aidevplatform.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowStage implements Serializable {
    private String id;
    private String type; // "agent" | "sequential" | "parallel"
    private String name;
    private String agentTemplateId; // UUID as string, set when type == "agent"
    private Boolean pauseForReview; // null = inherit workflow default
    @Builder.Default
    private List<WorkflowStage> children = new ArrayList<>();
}
