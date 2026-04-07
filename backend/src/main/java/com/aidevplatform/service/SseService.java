package com.aidevplatform.service;

import com.aidevplatform.api.dto.AgentEventDto;
import com.aidevplatform.domain.entity.AgentEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Manages Server-Sent Event (SSE) connections for real-time push to Vaadin UI.
 * Supports multiple browser tabs per user via a list of emitters per userId.
 */
@Service
@Slf4j
public class SseService {

    // Map<userId, List<SseEmitter>> -- supports multiple browser tabs per user
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule());

    public SseEmitter subscribe(String userId) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(userId, emitter));
        emitter.onTimeout(() -> removeEmitter(userId, emitter));
        emitter.onError(e -> removeEmitter(userId, emitter));

        log.info("SSE subscription created for user: {}", userId);
        return emitter;
    }

    /**
     * Broadcasts an agent event to the module's project owner.
     */
    public void broadcastAgentEvent(AgentEvent event) {
        String ownerId = event.getRun().getModule().getProject().getOwner().getId().toString();
        AgentEventDto dto = mapToDto(event);
        sendToUser(ownerId, "agent-event", dto);
    }

    /**
     * Broadcasts a module status update to the owner.
     */
    public void broadcastModuleUpdate(UUID moduleId, String eventName) {
        // This requires resolving the owner; caller should pass ownerId directly
        // to avoid lazy loading issues outside transaction scope.
        // Kept here for symmetry -- use broadcastModuleUpdateToOwner for actual calls.
        log.debug("Module update broadcast: moduleId={}, event={}", moduleId, eventName);
    }

    public void broadcastModuleUpdateToOwner(String ownerId, UUID moduleId, String eventName) {
        sendToUser(ownerId, "module-update", Map.of("moduleId", moduleId, "event", eventName));
    }

    public void broadcastAgentAwaitingApproval(String ownerId, UUID runId) {
        sendToUser(ownerId, "awaiting-approval", Map.of("runId", runId));
    }

    public void broadcastRunRejected(String ownerId, UUID runId, String reason) {
        sendToUser(ownerId, "run-rejected", Map.of("runId", runId, "reason", reason));
    }

    public void broadcastModuleComplete(String ownerId, UUID moduleId) {
        sendToUser(ownerId, "module-complete", Map.of("moduleId", moduleId));
    }

    private void sendToUser(String userId, String eventName, Object data) {
        List<SseEmitter> userEmitters = emitters.getOrDefault(userId, List.of());
        if (userEmitters.isEmpty()) {
            log.debug("No active SSE emitters for user: {}", userId);
            return;
        }
        String payload;
        try {
            payload = objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize SSE payload for event: {}", eventName, e);
            return;
        }

        String finalPayload = payload;
        userEmitters.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event()
                        .name(eventName)
                        .data(finalPayload));
            } catch (Exception e) {
                log.warn("Failed to send SSE event to user {}: {}", userId, e.getMessage());
                removeEmitter(userId, emitter);
            }
        });
    }

    private void removeEmitter(String userId, SseEmitter emitter) {
        List<SseEmitter> userEmitters = emitters.get(userId);
        if (userEmitters != null) {
            userEmitters.remove(emitter);
            if (userEmitters.isEmpty()) {
                emitters.remove(userId);
            }
        }
        log.debug("SSE emitter removed for user: {}", userId);
    }

    private AgentEventDto mapToDto(AgentEvent event) {
        return AgentEventDto.builder()
                .eventId(event.getId())
                .runId(event.getRun().getId())
                .agentName(event.getRun().getAgentName())
                .eventType(event.getEventType().name())
                .message(event.getMessage())
                .severity(event.getSeverity().name())
                .payload(event.getPayload())
                .timestamp(event.getCreatedAt())
                .build();
    }
}
