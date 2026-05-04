package com.aidevplatform.api;

import com.aidevplatform.api.dto.ChatMessageRequestDto;
import com.aidevplatform.service.AgentChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/runs/{runId}/chat")
@RequiredArgsConstructor
public class AgentChatController {

    private final AgentChatService agentChatService;

    @GetMapping
    public List<Map<String, Object>> getChatHistory(@PathVariable UUID runId) {
        return agentChatService.getChatHistory(runId).stream()
                .map(m -> Map.<String, Object>of(
                        "id", m.getId().toString(),
                        "role", m.getRole(),
                        "content", m.getContent(),
                        "revisionNumber", m.getRevisionNumber(),
                        "createdAt", m.getCreatedAt() != null ? m.getCreatedAt().toString() : ""))
                .toList();
    }

    @PostMapping
    public ResponseEntity<Void> sendFeedback(@PathVariable UUID runId,
                                              @RequestBody ChatMessageRequestDto request) {
        agentChatService.sendFeedback(runId, request.getMessage());
        return ResponseEntity.ok().build();
    }
}
