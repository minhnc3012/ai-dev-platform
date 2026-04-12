package com.aidevplatform.domain.entity;

import com.aidevplatform.domain.model.*;
import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "agent_reports")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentReport extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @OneToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "run_id", nullable = false)
    private AgentRun run;

    @Column(columnDefinition = "text", nullable = false)
    private String summary;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    @Builder.Default
    private List<Deliverable> deliverables = new ArrayList<>();

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    @Builder.Default
    private List<Issue> issuesFound = new ArrayList<>();

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    @Builder.Default
    private List<NextStep> nextSteps = new ArrayList<>();

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    @Builder.Default
    private List<OwnerDecision> ownerDecisionsNeeded = new ArrayList<>();

    @Column(precision = 5, scale = 2)
    private BigDecimal confidenceScore;

    private String confidenceReason;
    private Integer tokensUsed;
    private Integer durationSeconds;
}
