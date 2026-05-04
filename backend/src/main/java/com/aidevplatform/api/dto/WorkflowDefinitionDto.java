package com.aidevplatform.api.dto;

import com.aidevplatform.domain.model.WorkflowStage;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowDefinitionDto {
    private UUID id;
    private UUID projectId;
    private String name;
    private String description;
    private Boolean isTemplate;
    private Boolean defaultPauseForReview;
    @Builder.Default
    private List<WorkflowStage> stages = new ArrayList<>();
}
