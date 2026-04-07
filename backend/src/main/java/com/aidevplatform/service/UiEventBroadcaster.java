package com.aidevplatform.service;

import com.aidevplatform.api.dto.AgentEventDto;
import com.vaadin.flow.component.UI;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Bridges SSE events from background threads to Vaadin UI instances.
 *
 * Views that want to receive live agent events call registerListener() on attach
 * and unregisterListener() on detach. When an event arrives, the broadcaster
 * iterates all registered consumers and invokes UI.access() for thread safety.
 *
 * This satisfies spec section 16: "Always use UI.getCurrent().access(() -> { ... })
 * when updating Vaadin components from a background thread."
 */
@Service
@Slf4j
public class UiEventBroadcaster {

    /**
     * Registered listeners keyed by a unique registration ID.
     * Each entry holds the UI instance and the consumer to invoke.
     */
    private final Map<UUID, ListenerEntry> listeners = new ConcurrentHashMap<>();

    /**
     * Registers a UI listener for a specific module's agent events.
     *
     * @param moduleId UUID of the module being watched.
     * @param ui       The Vaadin UI instance of the subscriber.
     * @param consumer The callback invoked (within UI.access) when an event arrives.
     * @return Registration ID — pass to unregisterListener() on view detach.
     */
    public UUID registerListener(UUID moduleId, UI ui, Consumer<AgentEventDto> consumer) {
        UUID regId = UUID.randomUUID();
        listeners.put(regId, new ListenerEntry(moduleId, ui, consumer));
        log.debug("UI listener registered: regId={}, moduleId={}", regId, moduleId);
        return regId;
    }

    public void unregisterListener(UUID registrationId) {
        listeners.remove(registrationId);
        log.debug("UI listener unregistered: regId={}", registrationId);
    }

    /**
     * Broadcasts an agent event to all UI listeners watching the event's module.
     * Called from SseService (background thread) — uses UI.access() for safety.
     */
    public void broadcast(AgentEventDto event, UUID moduleId) {
        listeners.forEach((regId, entry) -> {
            if (entry.moduleId().equals(moduleId)) {
                entry.ui().access(() -> {
                    try {
                        entry.consumer().accept(event);
                    } catch (Exception e) {
                        log.warn("UI event consumer threw exception: regId={}", regId, e);
                    }
                });
            }
        });
    }

    private record ListenerEntry(UUID moduleId, UI ui, Consumer<AgentEventDto> consumer) {}
}
