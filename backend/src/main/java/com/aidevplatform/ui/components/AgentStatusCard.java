package com.aidevplatform.ui.components;

import com.aidevplatform.api.dto.AgentEventDto;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;

/**
 * Card component showing real-time status for a single agent in the pipeline.
 * Clickable — click triggers the detail panel to show that agent's report/file/log.
 */
public class AgentStatusCard extends VerticalLayout {

    private final String agentName;
    private final Span statusBadge;
    private final Span currentTaskLabel;
    private final ProgressBar progressBar;
    private final Span timeLabel;
    private final Span approvalBanner;

    private long startEpoch = 0;
    private boolean selected = false;

    public AgentStatusCard(String agentName) {
        this.agentName = agentName;
        addClassName("agent-status-card");
        setWidth("160px");
        setMinWidth("140px");
        setPadding(true);
        setSpacing(false);
        getStyle()
                .set("border", "2px solid var(--lumo-contrast-10pct)")
                .set("border-radius", "var(--lumo-border-radius-m)")
                .set("cursor", "pointer")
                .set("transition", "box-shadow 0.15s, border-color 0.15s")
                .set("background", "var(--lumo-base-color)");

        // Agent icon + name row
        Div titleRow = new Div();
        titleRow.getStyle().set("display", "flex").set("align-items", "center").set("gap", "6px");
        Span icon = new Span(resolveIcon(agentName));
        icon.getStyle().set("font-size", "20px");
        Span title = new Span(resolveDisplayName(agentName));
        title.getStyle().set("font-weight", "600").set("font-size", "13px");
        titleRow.add(icon, title);

        statusBadge = new Span("Idle");
        statusBadge.addClassName("status-badge-idle");
        statusBadge.getStyle().set("font-size", "11px").set("margin-top", "4px");

        approvalBanner = new Span("Review needed");
        approvalBanner.getStyle()
                .set("font-size", "10px")
                .set("font-weight", "600")
                .set("color", "var(--lumo-warning-contrast-color)")
                .set("background", "var(--lumo-warning-color)")
                .set("padding", "2px 6px")
                .set("border-radius", "4px")
                .set("margin-top", "4px");
        approvalBanner.setVisible(false);

        currentTaskLabel = new Span("—");
        currentTaskLabel.getStyle()
                .set("font-size", "11px")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("margin-top", "4px")
                .set("word-break", "break-word");

        progressBar = new ProgressBar();
        progressBar.setIndeterminate(false);
        progressBar.setValue(0);
        progressBar.setVisible(false);
        progressBar.getStyle().set("margin-top", "4px");

        timeLabel = new Span("—");
        timeLabel.getStyle()
                .set("font-size", "10px")
                .set("color", "var(--lumo-tertiary-text-color)")
                .set("margin-top", "2px");

        add(titleRow, statusBadge, approvalBanner, currentTaskLabel, progressBar, timeLabel);

        // Hover effect via JS
        getElement().addEventListener("mouseenter",
                e -> getStyle().set("box-shadow", "0 2px 8px rgba(0,0,0,0.15)"));
        getElement().addEventListener("mouseleave",
                e -> { if (!selected) getStyle().remove("box-shadow"); });
    }

    /**
     * Marks this card as selected (highlighted border).
     * Clears selection on all sibling cards should be done by the caller.
     */
    public void setSelected(boolean select) {
        this.selected = select;
        if (select) {
            getStyle()
                    .set("border-color", "var(--lumo-primary-color)")
                    .set("box-shadow", "0 0 0 2px var(--lumo-primary-color-10pct)");
        } else {
            getStyle()
                    .set("border-color", "var(--lumo-contrast-10pct)")
                    .remove("box-shadow");
        }
    }

    /**
     * Updates card display based on an incoming agent event.
     * Must be called inside UI.access() when from a background thread.
     */
    public void updateStatus(AgentEventDto event) {
        switch (event.getEventType()) {
            case "STARTED" -> {
                setStatus("Running", "status-badge-running");
                approvalBanner.setVisible(false);
                progressBar.setIndeterminate(true);
                progressBar.setVisible(true);
                startEpoch = System.currentTimeMillis();
                currentTaskLabel.setText("Starting…");
            }
            case "THINKING" -> {
                currentTaskLabel.setText("Analysing…");
                updateElapsedTime();
            }
            case "TOOL_CALL" -> {
                currentTaskLabel.setText(truncate(event.getMessage(), 40));
                updateElapsedTime();
            }
            case "TOOL_RESULT", "INFO" -> updateElapsedTime();
            case "COMPLETED" -> {
                setStatus("Done", "status-badge-done");
                approvalBanner.setVisible(false);
                progressBar.setIndeterminate(false);
                progressBar.setValue(1.0);
                progressBar.setVisible(false);
                currentTaskLabel.setText("Completed");
                updateElapsedTime();
            }
            case "AWAITING_APPROVAL" -> {
                setStatus("Done", "status-badge-done");
                approvalBanner.setVisible(true);
                progressBar.setVisible(false);
                progressBar.setIndeterminate(false);
                currentTaskLabel.setText("Click to review");
                updateElapsedTime();
            }
            case "ERROR" -> {
                setStatus("Error", "status-badge-error");
                approvalBanner.setVisible(false);
                progressBar.setIndeterminate(false);
                progressBar.setVisible(false);
                currentTaskLabel.setText(truncate(event.getMessage(), 40));
            }
            case "WARNING" ->
                    currentTaskLabel.setText("⚠ " + truncate(event.getMessage(), 35));
        }
    }

    public void resetToIdle() {
        setStatus("Idle", "status-badge-idle");
        approvalBanner.setVisible(false);
        progressBar.setIndeterminate(false);
        progressBar.setValue(0);
        progressBar.setVisible(false);
        currentTaskLabel.setText("—");
        timeLabel.setText("—");
        startEpoch = 0;
    }

    public String getAgentName() {
        return agentName;
    }

    // -------------------------------------------------------------------------

    private void setStatus(String text, String cssClass) {
        statusBadge.setText(text);
        statusBadge.removeClassNames(
                "status-badge-idle", "status-badge-running",
                "status-badge-done", "status-badge-error", "status-badge-waiting");
        statusBadge.addClassName(cssClass);
    }

    private void updateElapsedTime() {
        if (startEpoch > 0) {
            long s = (System.currentTimeMillis() - startEpoch) / 1000;
            timeLabel.setText(s < 60 ? s + "s" : (s / 60) + "m " + (s % 60) + "s");
        }
    }

    private String truncate(String text, int max) {
        if (text == null) return "";
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private String resolveDisplayName(String name) {
        return switch (name) {
            case "pm" -> "PM Agent";
            case "architect" -> "Architect";
            case "dev" -> "Developer";
            case "qa" -> "QA Engineer";
            case "docs" -> "Tech Writer";
            default -> name;
        };
    }

    private String resolveIcon(String name) {
        return switch (name) {
            case "pm" -> "📋";
            case "architect" -> "🏗️";
            case "dev" -> "💻";
            case "qa" -> "🔬";
            case "docs" -> "📚";
            default -> "🤖";
        };
    }
}
