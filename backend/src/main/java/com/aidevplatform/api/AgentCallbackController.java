package com.aidevplatform.api;

import com.aidevplatform.api.dto.AgentCallbackDto;
import com.aidevplatform.api.dto.AgentEventRequest;
import com.aidevplatform.domain.enums.AgentEventType;
import com.aidevplatform.service.AgentEventService;
import com.aidevplatform.service.AgentOrchestrator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Internal REST API for Python agent callbacks.
 * Receives real-time events and completion reports from the Python agent layer.
 */
@RestController
@RequestMapping("/api/internal/agent")
@RequiredArgsConstructor
@Slf4j
public class AgentCallbackController {

    private final AgentEventService agentEventService;
    private final AgentOrchestrator agentOrchestrator;

    /**
     * Receives a real-time event from a Python agent and broadcasts it via SSE.
     */
    @PostMapping("/event")
    public ResponseEntity<Void> pushEvent(@RequestBody AgentEventRequest req) {
        try {
            AgentEventType eventType = AgentEventType.valueOf(req.getEventType().toUpperCase());
            agentEventService.saveAndBroadcast(
                    req.getRunId(), eventType, req.getMessage(), req.getPayload());
            return ResponseEntity.ok().build();
        } catch (IllegalArgumentException e) {
            log.warn("Unknown event type received: {}", req.getEventType());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * Receives the completion callback and structured report from a Python agent.
     */
    @PostMapping("/complete")
    public ResponseEntity<Void> completeRun(@RequestBody AgentCallbackDto callback) {
        agentOrchestrator.handleAgentComplete(callback);
        return ResponseEntity.ok().build();
    }

    /**
     * Returns all agent runs that were RUNNING when the service last stopped.
     * Called by the Python agent service on startup to resume interrupted work.
     * Each entry is a full task config map identical to what Redis would have dispatched.
     */
    @GetMapping("/recovery/interrupted")
    public ResponseEntity<List<Map<String, Object>>> getInterruptedRuns() {
        return ResponseEntity.ok(agentOrchestrator.getInterruptedRuns());
    }
}
