package com.aidevplatform.api;

import com.aidevplatform.api.dto.AgentCallbackDto;
import com.aidevplatform.api.dto.AgentEventRequest;
import com.aidevplatform.domain.enums.AgentEventType;
import com.aidevplatform.service.AgentEventService;
import com.aidevplatform.service.AgentOrchestrator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Unit tests for AgentCallbackController using standalone MockMvc.
 * No Spring context is loaded — all dependencies are mocked with Mockito.
 */
@ExtendWith(MockitoExtension.class)
class AgentCallbackControllerTest {

    @Mock AgentEventService agentEventService;
    @Mock AgentOrchestrator agentOrchestrator;

    @InjectMocks AgentCallbackController controller;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setup() {
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    // -------------------------------------------------------------------------
    // POST /api/internal/agent/event
    // -------------------------------------------------------------------------

    @Test
    void pushEvent_returns200AndDelegatesForKnownType() throws Exception {
        AgentEventRequest req = new AgentEventRequest();
        req.setRunId(UUID.randomUUID());
        req.setEventType("STARTED");
        req.setMessage("Agent started");

        mockMvc.perform(post("/api/internal/agent/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk());

        verify(agentEventService).saveAndBroadcast(
                eq(req.getRunId()), eq(AgentEventType.STARTED), eq("Agent started"), any());
    }

    @Test
    void pushEvent_returns400ForUnknownEventType() throws Exception {
        AgentEventRequest req = new AgentEventRequest();
        req.setRunId(UUID.randomUUID());
        req.setEventType("UNKNOWN_TYPE");
        req.setMessage("hello");

        mockMvc.perform(post("/api/internal/agent/event")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void pushEvent_allKnownEventTypesAreAccepted() throws Exception {
        for (AgentEventType type : AgentEventType.values()) {
            AgentEventRequest req = new AgentEventRequest();
            req.setRunId(UUID.randomUUID());
            req.setEventType(type.name());
            req.setMessage("test");

            mockMvc.perform(post("/api/internal/agent/event")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(req)))
                    .andExpect(status().isOk());
        }
    }

    // -------------------------------------------------------------------------
    // POST /api/internal/agent/complete
    // -------------------------------------------------------------------------

    @Test
    void completeRun_returns200AndDelegates() throws Exception {
        AgentCallbackDto dto = new AgentCallbackDto();
        dto.setRunId(UUID.randomUUID());
        dto.setTokensUsed(200);

        mockMvc.perform(post("/api/internal/agent/complete")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto)))
                .andExpect(status().isOk());

        verify(agentOrchestrator).handleAgentComplete(any(AgentCallbackDto.class));
    }

    // -------------------------------------------------------------------------
    // GET /api/internal/agent/recovery/interrupted
    // -------------------------------------------------------------------------

    @Test
    void getInterruptedRuns_returns200WithList() throws Exception {
        Map<String, Object> task1 = Map.of("run_id", "r1", "agent_name", "pm");
        Map<String, Object> task2 = Map.of("run_id", "r2", "agent_name", "architect");
        when(agentOrchestrator.getInterruptedRuns()).thenReturn(List.of(task1, task2));

        mockMvc.perform(get("/api/internal/agent/recovery/interrupted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].run_id").value("r1"))
                .andExpect(jsonPath("$[1].agent_name").value("architect"));
    }

    @Test
    void getInterruptedRuns_returnsEmptyArrayWhenNoneInterrupted() throws Exception {
        when(agentOrchestrator.getInterruptedRuns()).thenReturn(List.of());

        mockMvc.perform(get("/api/internal/agent/recovery/interrupted"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }
}
