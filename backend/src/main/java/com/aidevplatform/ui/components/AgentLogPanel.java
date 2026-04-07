package com.aidevplatform.ui.components;

import com.aidevplatform.api.dto.AgentEventDto;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;

import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Dark-themed log panel displaying real-time agent events in chronological order.
 */
public class AgentLogPanel extends Div {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final int MAX_LOG_ENTRIES = 500;

    private int entryCount = 0;

    public AgentLogPanel() {
        addClassName("agent-log-panel");
        getStyle()
                .set("background", "#1a1a1a")
                .set("color", "#9FE1CB")
                .set("font-family", "monospace")
                .set("font-size", "12px")
                .set("padding", "12px")
                .set("border-radius", "8px")
                .set("line-height", "1.8")
                .set("overflow-y", "auto")
                .set("max-height", "400px")
                .set("width", "100%");

        Span placeholder = new Span("Waiting for agent activity...");
        placeholder.getStyle().set("color", "#5F5E5A");
        add(placeholder);
    }

    /**
     * Appends a new agent event to the log.
     * Must be called from within UI.access() when originating from a background thread.
     */
    public void appendEvent(AgentEventDto event) {
        if (entryCount == 0) {
            removeAll(); // clear placeholder
        }
        if (entryCount >= MAX_LOG_ENTRIES) {
            // Remove oldest entry to cap memory usage
            if (getChildren().findFirst().isPresent()) {
                remove(getChildren().findFirst().get());
            }
        }

        Div entry = buildLogEntry(event);
        add(entry);
        entryCount++;

        // Scroll to bottom via JavaScript
        getElement().executeJs("this.scrollTop = this.scrollHeight");
    }

    /**
     * Loads historical events for a module (called on initial view load).
     */
    public void loadHistory(List<AgentEventDto> events) {
        removeAll();
        entryCount = 0;
        if (events.isEmpty()) {
            Span placeholder = new Span("No events recorded yet.");
            placeholder.getStyle().set("color", "#5F5E5A");
            add(placeholder);
            return;
        }
        events.forEach(this::appendEvent);
    }

    private Div buildLogEntry(AgentEventDto event) {
        Div entry = new Div();
        entry.addClassName("log-entry");

        // Timestamp
        Span timestamp = new Span("[" + formatTime(event) + "] ");
        timestamp.addClassName("log-timestamp");

        // Agent name
        Span agentSpan = new Span("[" + event.getAgentName() + "] ");
        agentSpan.addClassName("log-agent-name");

        // Message with type-specific styling
        Span message = new Span(event.getMessage());
        applyMessageStyle(message, event.getEventType());

        entry.add(timestamp, agentSpan, message);
        return entry;
    }

    private void applyMessageStyle(Span message, String eventType) {
        switch (eventType) {
            case "THINKING" -> message.addClassName("log-thinking");
            case "WARNING" -> message.addClassName("log-warning");
            case "ERROR" -> message.addClassName("log-error");
            case "COMPLETED" -> message.addClassName("log-success");
            default -> {} // default green color from panel CSS
        }
    }

    private String formatTime(AgentEventDto event) {
        if (event.getTimestamp() != null) {
            return event.getTimestamp().format(TIME_FMT);
        }
        return java.time.LocalTime.now().format(TIME_FMT);
    }
}
