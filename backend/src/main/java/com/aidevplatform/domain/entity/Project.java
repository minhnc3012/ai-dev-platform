package com.aidevplatform.domain.entity;

import com.aidevplatform.domain.enums.ProjectStatus;
import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "projects")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Project extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_config_id")
    private AiConfig aiConfig;

    @Enumerated(EnumType.STRING)
    private ProjectStatus status = ProjectStatus.ACTIVE;

    private String gitRepoUrl;

    // Absolute path on the server where agents will write generated files.
    // Structure: {workspacePath}/{moduleName}/{agentName}/
    @Column(columnDefinition = "text")
    private String workspacePath;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL)
    private List<Module> modules = new ArrayList<>();

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL)
    private List<ProjectContextDoc> contextDocs = new ArrayList<>();
}
