package com.aidevplatform.domain.entity;

import com.aidevplatform.domain.enums.AgentRunStatus;
import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "agent_runs", uniqueConstraints = {
    @jakarta.persistence.UniqueConstraint(columnNames = {"module_id", "agent_name"})
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
    private String agentName; // "pm" | "architect" | "dev" | "qa" | "docs"

    private Integer runOrder;

    @Enumerated(EnumType.STRING)
    private AgentRunStatus status = AgentRunStatus.PENDING;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Integer durationSeconds;
    private Integer tokensUsed = 0;
    private Integer retryCount = 0;
    private String errorMessage;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL)
    @OrderBy("createdAt ASC")
    private List<AgentEvent> events = new ArrayList<>();

    @OneToOne(mappedBy = "run", cascade = CascadeType.ALL)
    private AgentReport report;
}
