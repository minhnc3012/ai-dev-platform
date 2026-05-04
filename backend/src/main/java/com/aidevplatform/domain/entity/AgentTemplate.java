package com.aidevplatform.domain.entity;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.*;

@Entity
@Table(name = "agent_templates")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTemplate extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id")
    private Project project;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String agentKey;

    @Column(columnDefinition = "text", nullable = false)
    private String role;

    @Column(columnDefinition = "text", nullable = false)
    private String goal;

    @Column(columnDefinition = "text")
    private String backstoryTemplate;

    @Column(columnDefinition = "text")
    private String taskDescriptionTemplate;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    @Builder.Default
    private ObjectNode llmConfig = JsonNodeFactory.instance.objectNode();
}
