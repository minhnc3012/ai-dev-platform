package com.aidevplatform.service;

import com.aidevplatform.api.dto.AgentEventDto;
import com.aidevplatform.domain.entity.AgentEvent;
import com.aidevplatform.domain.entity.AgentRun;
import com.aidevplatform.domain.enums.AgentEventType;
import com.aidevplatform.domain.enums.EventSeverity;
import com.aidevplatform.repository.AgentEventRepository;
import com.aidevplatform.repository.AgentRunRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * Persists agent events and broadcasts them via SSE for real-time UI updates.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentEventService {

    private final AgentEventRepository eventRepository;
    private final AgentRunRepository agentRunRepository;
    private final SseService sseService;
    private final UiEventBroadcaster uiEventBroadcaster;

    /**
     * Persists a new agent event and immediately broadcasts it to the owner's SSE connection
     * so the monitor view updates in real time.
     */
    @Transactional
    public AgentEvent saveAndBroadcast(UUID runId, AgentEventType type,
                                        String message, Map<String, Object> payload) {
        // Handle orphaned events gracefully - e.g., when tables were truncated
        // but background processes still send events
        AgentRun run = agentRunRepository.findById(runId).orElse(null);
        if (run == null) {
            log.warn("AgentRun not found, skipping event. This may indicate an orphaned process: runId={}, type={}", runId, type);
            return null;
        }

        AgentEvent event = AgentEvent.builder()
                .run(run)
                .eventType(type)
                .message(message)
                .payload(payload)
                .severity(resolveSeverity(type))
                .build();

        AgentEvent saved = eventRepository.save(event);
        sseService.broadcastAgentEvent(saved);

        // Also broadcast to any open Vaadin UI instances watching this module
        UUID moduleId = run.getModule().getId();
        AgentEventDto dto = AgentEventDto.builder()
                .eventId(saved.getId())
                .runId(run.getId())
                .agentName(run.getAgentName())
                .eventType(type.name())
                .message(message)
                .severity(resolveSeverity(type).name())
                .payload(payload)
                .timestamp(saved.getCreatedAt())
                .build();
        uiEventBroadcaster.broadcast(dto, moduleId);

        log.debug("Agent event persisted and broadcast: runId={}, type={}", runId, type);
        return saved;
    }

    private EventSeverity resolveSeverity(AgentEventType type) {
        return switch (type) {
            case ERROR -> EventSeverity.ERROR;
            case WARNING -> EventSeverity.WARNING;
            default -> EventSeverity.INFO;
        };
    }
}
