package com.aidevplatform.api;

import com.aidevplatform.service.SseService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * SSE subscription endpoint for the Vaadin UI to receive real-time agent events.
 */
@RestController
@RequestMapping("/api/internal/agent")
@RequiredArgsConstructor
@Slf4j
public class SseController {

    private final SseService sseService;

    /**
     * SSE subscription endpoint -- Vaadin UI connects here to receive real-time events.
     */
    @GetMapping("/sse/{userId}")
    public SseEmitter subscribe(@PathVariable String userId) {
        log.info("New SSE subscription for user: {}", userId);
        return sseService.subscribe(userId);
    }
}
