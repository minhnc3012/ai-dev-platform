package com.aidevplatform.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.*;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentTemplateDto {
    private UUID id;
    private UUID projectId;
    private String name;
    private String agentKey;
    private String role;
    private String goal;
    private String backstoryTemplate;
    private String taskDescriptionTemplate;
    @Builder.Default
    private ObjectNode llmConfig = JsonNodeFactory.instance.objectNode();
}
