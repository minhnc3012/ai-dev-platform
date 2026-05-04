package com.aidevplatform.domain.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "agent_chat_messages")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentChatMessage extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    private AgentRun run;

    @Column(nullable = false)
    private String role; // "user" | "assistant"

    @Column(columnDefinition = "text", nullable = false)
    private String content;

    @Builder.Default
    private Integer revisionNumber = 0;
}
