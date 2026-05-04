package com.aidevplatform.domain.entity;

import com.aidevplatform.domain.enums.ModuleStatus;
import com.aidevplatform.domain.model.UserStory;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.*;
import lombok.Builder.Default;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "modules")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Module extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(columnDefinition = "text")
    private String rawRequirement;

    private String reqFilePath;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    @Default
    private List<UserStory> parsedStories = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    @Default
    private ModuleStatus status = ModuleStatus.DRAFT;

    private String currentAgent;

    @Column(name = "workflow_id")
    private java.util.UUID workflowId; // optional; null = legacy hardcoded flow

    @OneToMany(mappedBy = "module", cascade = CascadeType.ALL)
    @OrderBy("runOrder ASC")
    @Default
    private List<AgentRun> agentRuns = new ArrayList<>();
}
