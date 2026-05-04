package com.aidevplatform.service;

import com.aidevplatform.domain.entity.AgentChatMessage;
import com.aidevplatform.domain.entity.AgentRun;
import com.aidevplatform.domain.enums.AgentRunStatus;
import com.aidevplatform.repository.AgentChatMessageRepository;
import com.aidevplatform.repository.AgentRunRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentChatService {

    private final AgentRunRepository agentRunRepository;
    private final AgentChatMessageRepository agentChatMessageRepository;
    private final AgentOrchestrator agentOrchestrator;
    private final RedisTemplate<String, String> redisTemplate;
    private final SseService sseService;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Transactional(readOnly = true)
    public List<AgentChatMessage> getChatHistory(UUID runId) {
        return agentChatMessageRepository.findByRunIdOrderByCreatedAtAsc(runId);
    }

    @Transactional
    public void sendFeedback(UUID runId, String userMessage) {
        AgentRun run = agentRunRepository.findById(runId)
                .orElseThrow(() -> new EntityNotFoundException("AgentRun not found: " + runId));

        if (run.getStatus() != AgentRunStatus.AWAITING_APPROVAL
                && run.getStatus() != AgentRunStatus.COMPLETED) {
            throw new IllegalStateException(
                    "Run " + runId + " is not awaiting review (status=" + run.getStatus() + ")");
        }

        List<AgentChatMessage> existing = agentChatMessageRepository.findByRunIdOrderByCreatedAtAsc(runId);
        int revisionNumber = (int) existing.stream().filter(m -> "user".equals(m.getRole())).count() + 1;

        AgentChatMessage msg = AgentChatMessage.builder()
                .run(run)
                .role("user")
                .content(userMessage)
                .revisionNumber(revisionNumber)
                .build();
        agentChatMessageRepository.save(msg);

        run.setStatus(AgentRunStatus.RUNNING);
        run.setStartedAt(LocalDateTime.now());
        run.setCompletedAt(null);
        agentRunRepository.save(run);

        Map<String, Object> taskConfig = agentOrchestrator.buildFullTaskConfig(run);

        List<Map<String, String>> chatHistory = agentChatMessageRepository
                .findByRunIdOrderByCreatedAtAsc(runId).stream()
                .map(m -> Map.of("role", m.getRole(), "content", m.getContent()))
                .toList();
        taskConfig.put("chat_history", chatHistory);
        taskConfig.put("is_revision", true);

        try {
            String taskJson = objectMapper.writeValueAsString(taskConfig);
            redisTemplate.convertAndSend("agent:next", taskJson);
            log.info("Chat revision dispatched for run={}, revision={}", runId, revisionNumber);
        } catch (JsonProcessingException e) {
            log.error("Failed to dispatch chat revision for run={}", runId, e);
            run.setStatus(AgentRunStatus.AWAITING_APPROVAL);
            agentRunRepository.save(run);
            throw new RuntimeException("Failed to dispatch revision", e);
        }

        String ownerId = run.getModule().getProject().getOwner().getId().toString();
        sseService.broadcastAgentAwaitingApproval(ownerId, runId);
    }
}
