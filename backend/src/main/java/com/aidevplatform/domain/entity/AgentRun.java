package com.aidevplatform.domain.entity;

import com.aidevplatform.domain.enums.AgentRunStatus;
import jakarta.persistence.*;
import lombok.*;
import lombok.Builder.Default;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "agent_runs", uniqueConstraints = {
    @jakarta.persistence.UniqueConstraint(columnNames = {"module_id", "stage_id"})
})
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentRun extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_id", nullable = false)
    private Module module;

    @Column(nullable = false)
    private String agentName; // "pm" | "architect" | "dev" | "qa" | "docs" | custom name

    private String stageId; // workflow stage ID; defaults to agentName for legacy runs

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "agent_template_id")
    private AgentTemplate agentTemplate; // null for legacy hardcoded-flow runs

    private Integer runOrder;

    @Enumerated(EnumType.STRING)
    @Default
    private AgentRunStatus status = AgentRunStatus.PENDING;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Integer durationSeconds;
    @Default
    private Integer tokensUsed = 0;
    @Default
    private Integer retryCount = 0;
    private String errorMessage;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL)
    @OrderBy("createdAt ASC")
    @Default
    private List<AgentEvent> events = new ArrayList<>();

    @OneToOne(mappedBy = "run", cascade = CascadeType.ALL)
    private AgentReport report;
}
