package com.aidevplatform.domain.entity;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Builder.Default;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "agent_sessions")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentSession implements Serializable {
    private static final long serialVersionUID = 1L;

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_id", nullable = false)
    private Module module;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    private AgentRun run;

    @Column(columnDefinition = "text", nullable = false)
    private String summary; // LLM-compressed summary of this agent's work

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private ObjectNode metadata; // Session metadata (tokens used, duration, decision points)

    @Column(length = 5000)
    private String reasoningSummary; // Why decisions were made (design choices, trade-offs)

    @Column(name = "last_message_at")
    private LocalDateTime lastMessageAt;

    @Column(name = "message_count")
    private Integer messageCount;

    @Column(name = "status", nullable = false)
    @Enumerated(EnumType.STRING)
    @Default
    private SessionStatus status = SessionStatus.ACTIVE;

    public enum SessionStatus {
        ACTIVE,
        COMPRESSED,
        ARCHIVED
    }
}
