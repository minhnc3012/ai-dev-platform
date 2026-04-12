package com.aidevplatform.domain.entity;

import com.aidevplatform.domain.enums.AgentEventType;
import com.aidevplatform.domain.enums.EventSeverity;
import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import lombok.*;

import com.fasterxml.jackson.databind.node.ObjectNode;

@Entity
@Table(name = "agent_events")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentEvent extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    private AgentRun run;

    @Enumerated(EnumType.STRING)
    private AgentEventType eventType;

    @Column(columnDefinition = "text", nullable = false)
    private String message;

    @Column(columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private ObjectNode payload;

    @Enumerated(EnumType.STRING)
    private EventSeverity severity = EventSeverity.INFO;
}
