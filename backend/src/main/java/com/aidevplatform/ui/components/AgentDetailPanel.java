package com.aidevplatform.ui.components;

import com.aidevplatform.api.dto.AgentEventDto;
import com.aidevplatform.domain.entity.AgentReport;
import com.aidevplatform.domain.entity.AgentRun;
import com.aidevplatform.domain.enums.AgentRunStatus;
import com.aidevplatform.domain.model.Deliverable;
import com.aidevplatform.domain.model.Issue;
import com.aidevplatform.domain.model.OwnerDecision;
import com.aidevplatform.service.AgentOrchestrator;
import com.aidevplatform.service.FileStorageService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.progressbar.ProgressBarVariant;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.theme.lumo.LumoUtility;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Detail panel shown in the SplitLayout secondary pane.
 * When the owner clicks an agent card, this panel renders:
 *  - Agent status header with timing
 *  - Approve / Reject buttons (when AWAITING_APPROVAL)
 *  - Tabs: Report | Output File | Activity Log
 */
@Slf4j
public class AgentDetailPanel extends VerticalLayout {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AgentOrchestrator agentOrchestrator;
    private final FileStorageService fileStorageService;

    // State for the currently displayed run
    private AgentRun currentRun;
    private HorizontalLayout approvalBar;
    private TabSheet tabSheet;
    private VerticalLayout reportContent;
    private Div fileContent;
    private AgentLogPanel agentLog;
    private Span headerStatus;
    private Span headerTiming;

    public AgentDetailPanel(AgentOrchestrator agentOrchestrator,
                             FileStorageService fileStorageService) {
        this.agentOrchestrator = agentOrchestrator;
        this.fileStorageService = fileStorageService;

        setSizeFull();
        setPadding(false);
        setSpacing(false);
        getStyle().set("overflow", "hidden");

        showPlaceholder();
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Loads the detail view for a given agent run.
     * Must be called inside UI.access() when invoked from a background thread.
     */
    public void showForRun(AgentRun run, Optional<AgentReport> reportOpt,
                            List<AgentEventDto> events) {
        this.currentRun = run;
        removeAll();

        // Header
        add(buildHeader(run, reportOpt));

        // Approval bar (only shown when AWAITING_APPROVAL)
        approvalBar = buildApprovalBar(run);
        add(approvalBar);

        // Tabs
        tabSheet = new TabSheet();
        tabSheet.setSizeFull();

        reportContent = buildReportTab(run, reportOpt);
        tabSheet.add("📄 Report", reportContent);

        fileContent = buildFileTab(run, reportOpt);
        tabSheet.add("📝 Output File", fileContent);

        agentLog = new AgentLogPanel();
        agentLog.setSizeFull();
        agentLog.getStyle().set("max-height", "none").set("height", "100%");
        agentLog.loadHistory(events);
        tabSheet.add("📋 Activity Log", agentLog);

        add(tabSheet);
        setFlexGrow(1, tabSheet);
    }

    /**
     * Handles a live incoming event for the currently displayed agent.
     * Must be called inside UI.access().
     */
    public void handleEvent(AgentEventDto event) {
        if (agentLog != null) {
            agentLog.appendEvent(event);
        }
        if (currentRun == null) return;
        if (!event.getAgentName().equals(currentRun.getAgentName())) return;

        // Update header status live
        if (headerStatus != null) {
            String et = event.getEventType();
            if ("COMPLETED".equals(et) || "AWAITING_APPROVAL".equals(et)) {
                headerStatus.setText(et.equals("AWAITING_APPROVAL") ? "Review Needed" : "Completed");
            } else if ("STARTED".equals(et)) {
                headerStatus.setText("Running");
            } else if ("ERROR".equals(et)) {
                headerStatus.setText("Error");
            }
        }

        // Show approval bar live when approval event arrives
        if ("AWAITING_APPROVAL".equals(event.getEventType()) && approvalBar != null) {
            approvalBar.setVisible(true);
        }
    }

    /**
     * Forces the approval bar to be visible and updates the status label.
     * Called when an AWAITING_APPROVAL event arrives to guarantee visibility
     * regardless of any DB timing edge cases.
     */
    public void forceShowApproval() {
        if (approvalBar != null) {
            approvalBar.setVisible(true);
        }
        if (headerStatus != null) {
            headerStatus.setText("Review Needed");
            headerStatus.getStyle().set("background", "#E65100");
        }
    }

    /**
     * Refreshes the approval bar visibility based on the current run's persisted status.
     */
    public void refreshApprovalState(AgentRun run) {
        if (approvalBar != null && currentRun != null
                && currentRun.getId().equals(run.getId())) {
            approvalBar.setVisible(run.getStatus() == AgentRunStatus.AWAITING_APPROVAL);
        }
    }

    /** Returns the agent name currently displayed, or null if showing placeholder. */
    public String getCurrentAgentName() {
        return currentRun != null ? currentRun.getAgentName() : null;
    }

    // -------------------------------------------------------------------------
    // Builder helpers
    // -------------------------------------------------------------------------

    private void showPlaceholder() {
        removeAll();
        VerticalLayout ph = new VerticalLayout();
        ph.setSizeFull();
        ph.setAlignItems(Alignment.CENTER);
        ph.setJustifyContentMode(JustifyContentMode.CENTER);
        Span icon = new Span("👈");
        icon.getStyle().set("font-size", "48px");
        Span msg = new Span("Click an agent card to view details");
        msg.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.MEDIUM);
        ph.add(icon, msg);
        add(ph);
    }

    private VerticalLayout buildHeader(AgentRun run, Optional<AgentReport> reportOpt) {
        VerticalLayout header = new VerticalLayout();
        header.setPadding(true);
        header.setSpacing(false);
        header.getStyle()
                .set("background", "var(--lumo-contrast-5pct)")
                .set("border-bottom", "1px solid var(--lumo-contrast-10pct)");

        // Row 1: icon + name + status
        HorizontalLayout titleRow = new HorizontalLayout();
        titleRow.setAlignItems(Alignment.CENTER);
        titleRow.setSpacing(true);

        Span icon = new Span(resolveIcon(run.getAgentName()));
        icon.getStyle().set("font-size", "24px");

        H3 agentTitle = new H3(resolveDisplayName(run.getAgentName()));
        agentTitle.getStyle().set("margin", "0");

        headerStatus = new Span(resolveStatusText(run.getStatus()));
        headerStatus.getStyle()
                .set("font-size", "12px")
                .set("font-weight", "600")
                .set("padding", "2px 8px")
                .set("border-radius", "10px")
                .set("background", resolveStatusColor(run.getStatus()))
                .set("color", "white");

        titleRow.add(icon, agentTitle, headerStatus);

        // Row 2: timing info
        headerTiming = new Span(buildTimingText(run));
        headerTiming.addClassNames(LumoUtility.TextColor.SECONDARY, LumoUtility.FontSize.SMALL);

        header.add(titleRow, headerTiming);

        // Confidence score bar if report is present
        reportOpt.ifPresent(report -> {
            if (report.getConfidenceScore() != null) {
                HorizontalLayout confRow = new HorizontalLayout();
                confRow.setAlignItems(Alignment.CENTER);
                confRow.setSpacing(true);

                Span confLabel = new Span("Confidence: " + report.getConfidenceScore() + "%");
                confLabel.addClassNames(LumoUtility.FontSize.SMALL);

                ProgressBar bar = new ProgressBar();
                bar.setValue(report.getConfidenceScore().doubleValue() / 100.0);
                bar.setWidth("120px");
                if (report.getConfidenceScore().compareTo(new BigDecimal("70")) < 0) {
                    bar.addThemeVariants(ProgressBarVariant.LUMO_ERROR);
                }

                confRow.add(confLabel, bar);
                header.add(confRow);
            }
        });

        return header;
    }

    private HorizontalLayout buildApprovalBar(AgentRun run) {
        HorizontalLayout bar = new HorizontalLayout();
        bar.setWidthFull();
        bar.setAlignItems(Alignment.CENTER);
        bar.setSpacing(true);
        bar.setPadding(true);
        bar.getStyle()
                .set("background", "var(--lumo-warning-color-10pct)")
                .set("border-bottom", "1px solid var(--lumo-warning-color)")
                .set("flex-shrink", "0");

        Span warningIcon = new Span("⚠️");
        Span msg = new Span("This agent run is waiting for your approval before the pipeline continues.");
        msg.getStyle().set("font-size", "13px").set("flex", "1");

        Button approveBtn = new Button("✓ Approve", e -> onApprove(run.getId()));
        approveBtn.addThemeVariants(ButtonVariant.LUMO_SUCCESS, ButtonVariant.LUMO_PRIMARY);
        approveBtn.getStyle().set("font-weight", "600");

        Button rejectBtn = new Button("✗ Reject", e -> openRejectDialog(run.getId()));
        rejectBtn.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);

        bar.add(warningIcon, msg, approveBtn, rejectBtn);
        bar.setVisible(run.getStatus() == AgentRunStatus.AWAITING_APPROVAL);
        return bar;
    }

    private VerticalLayout buildReportTab(AgentRun run, Optional<AgentReport> reportOpt) {
        VerticalLayout layout = new VerticalLayout();
        layout.setWidthFull();
        layout.setPadding(true);
        layout.getStyle().set("overflow-y", "auto");

        if (reportOpt.isEmpty()) {
            Span empty;
            if (run.getStatus() == AgentRunStatus.PENDING) {
                empty = new Span("This agent has not started yet.");
            } else if (run.getStatus() == AgentRunStatus.RUNNING) {
                empty = new Span("Agent is currently running — report will appear when done.");
            } else {
                empty = new Span("No report available for this run.");
            }
            empty.addClassNames(LumoUtility.TextColor.SECONDARY);
            layout.add(empty);
            return layout;
        }

        AgentReport report = reportOpt.get();

        // Summary
        if (report.getSummary() != null && !report.getSummary().isBlank()) {
            Div summaryBox = new Div();
            summaryBox.getStyle()
                    .set("background", "var(--lumo-contrast-5pct)")
                    .set("padding", "12px")
                    .set("border-radius", "var(--lumo-border-radius-m)")
                    .set("margin-bottom", "12px");
            Span summaryLabel = new Span("Summary");
            summaryLabel.getStyle().set("font-weight", "600").set("font-size", "12px")
                    .set("text-transform", "uppercase").set("color", "var(--lumo-secondary-text-color)");
            Paragraph summaryText = new Paragraph(report.getSummary());
            summaryText.getStyle().set("margin", "4px 0 0 0").set("font-size", "14px");
            summaryBox.add(summaryLabel, summaryText);
            layout.add(summaryBox);
        }

        // Deliverables
        if (report.getDeliverables() != null && !report.getDeliverables().isEmpty()) {
            layout.add(buildDeliverablesSection(report.getDeliverables()));
        }

        // Issues
        if (report.getIssuesFound() != null && !report.getIssuesFound().isEmpty()) {
            layout.add(buildIssuesSection(report.getIssuesFound()));
        }

        // Owner decisions
        if (report.getOwnerDecisionsNeeded() != null && !report.getOwnerDecisionsNeeded().isEmpty()) {
            layout.add(buildDecisionsSection(report.getOwnerDecisionsNeeded()));
        }

        return layout;
    }

    private Div buildFileTab(AgentRun run, Optional<AgentReport> reportOpt) {
        Div container = new Div();
        container.setSizeFull();
        container.getStyle().set("overflow", "hidden").set("display", "flex").set("flex-direction", "column");

        // Find the output file path from deliverables
        String filePath = reportOpt
                .flatMap(r -> r.getDeliverables() == null ? Optional.empty()
                        : r.getDeliverables().stream()
                        .filter(d -> d.getFilePath() != null && d.getFilePath().endsWith(".md"))
                        .map(Deliverable::getFilePath)
                        .findFirst())
                .orElse(null);

        if (filePath == null) {
            Div ph = new Div();
            ph.getStyle().set("padding", "16px");
            if (run.getStatus() == AgentRunStatus.PENDING || run.getStatus() == AgentRunStatus.RUNNING) {
                ph.add(new Span("Output file will appear here when the agent completes."));
            } else {
                ph.add(new Span("No output file found for this run."));
            }
            container.add(ph);
            return container;
        }

        // File path bar
        Div pathBar = new Div();
        pathBar.getStyle()
                .set("padding", "6px 12px")
                .set("background", "var(--lumo-contrast-5pct)")
                .set("border-bottom", "1px solid var(--lumo-contrast-10pct)")
                .set("font-size", "11px")
                .set("font-family", "monospace")
                .set("color", "var(--lumo-secondary-text-color)")
                .set("flex-shrink", "0");
        pathBar.add(new Span("📁 " + filePath));
        container.add(pathBar);

        // File content viewer
        Div viewer = new Div();
        viewer.getStyle()
                .set("flex", "1")
                .set("overflow-y", "auto")
                .set("padding", "16px")
                .set("font-family", "monospace")
                .set("font-size", "13px")
                .set("line-height", "1.7")
                .set("white-space", "pre-wrap")
                .set("word-break", "break-word")
                .set("background", "#1e1e1e")
                .set("color", "#d4d4d4");

        try {
            String content = fileStorageService.downloadAsString(filePath);
            // Render markdown-ish: wrap in pre, highlight headings
            viewer.add(renderMarkdown(content));
        } catch (Exception e) {
            log.warn("Could not read output file: {}", filePath, e);
            viewer.getStyle().set("color", "var(--lumo-error-color)");
            viewer.add(new Span("Could not read file: " + e.getMessage()));
        }

        container.add(viewer);
        return container;
    }

    /** Very lightweight markdown-to-HTML rendering (headings, bold, code blocks). */
    private Div renderMarkdown(String content) {
        Div wrapper = new Div();
        wrapper.setSizeFull();

        String[] lines = content.split("\n");
        Div currentPre = null;
        boolean inCodeBlock = false;

        for (String line : lines) {
            if (line.startsWith("```")) {
                if (!inCodeBlock) {
                    currentPre = new Div();
                    currentPre.getStyle()
                            .set("background", "#2d2d2d")
                            .set("padding", "8px")
                            .set("border-radius", "4px")
                            .set("margin", "4px 0")
                            .set("border-left", "3px solid var(--lumo-primary-color)");
                    inCodeBlock = true;
                } else {
                    if (currentPre != null) wrapper.add(currentPre);
                    currentPre = null;
                    inCodeBlock = false;
                }
                continue;
            }

            if (inCodeBlock && currentPre != null) {
                Span codeLine = new Span(line);
                codeLine.getStyle().set("display", "block").set("color", "#9cdcfe");
                currentPre.add(codeLine);
                continue;
            }

            Div row = new Div();
            if (line.startsWith("# ")) {
                Span h = new Span(line.substring(2));
                h.getStyle().set("font-size", "18px").set("font-weight", "700")
                        .set("color", "#4ec9b0").set("display", "block").set("margin", "12px 0 4px");
                row.add(h);
            } else if (line.startsWith("## ")) {
                Span h = new Span(line.substring(3));
                h.getStyle().set("font-size", "15px").set("font-weight", "600")
                        .set("color", "#569cd6").set("display", "block").set("margin", "8px 0 2px");
                row.add(h);
            } else if (line.startsWith("### ")) {
                Span h = new Span(line.substring(4));
                h.getStyle().set("font-size", "13px").set("font-weight", "600")
                        .set("color", "#9cdcfe").set("display", "block").set("margin", "6px 0 2px");
                row.add(h);
            } else if (line.startsWith("- ") || line.startsWith("* ")) {
                Span bullet = new Span("• " + line.substring(2));
                bullet.getStyle().set("display", "block").set("padding-left", "12px");
                row.add(bullet);
            } else if (line.isBlank()) {
                row.getStyle().set("height", "8px");
            } else {
                row.add(new Span(line));
            }
            wrapper.add(row);
        }
        return wrapper;
    }

    private VerticalLayout buildDeliverablesSection(List<Deliverable> deliverables) {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(false);
        section.setSpacing(false);
        H4 title = new H4("Deliverables");
        title.getStyle().set("margin", "0 0 4px 0");
        section.add(title);
        deliverables.forEach(d -> {
            Div item = new Div();
            item.getStyle().set("font-size", "13px").set("padding", "2px 0");
            Span type = new Span("[" + d.getType() + "] ");
            type.getStyle().set("color", "var(--lumo-primary-color)");
            Span name = new Span(d.getName());
            name.getStyle().set("font-weight", "500");
            item.add(type, name);
            if (d.getDescription() != null) {
                Span desc = new Span(" — " + d.getDescription());
                desc.getStyle().set("color", "var(--lumo-secondary-text-color)");
                item.add(desc);
            }
            section.add(item);
        });
        return section;
    }

    private VerticalLayout buildIssuesSection(List<Issue> issues) {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(false);
        section.setSpacing(false);
        H4 title = new H4("Issues Found (" + issues.size() + ")");
        title.getStyle().set("margin", "8px 0 4px 0");
        section.add(title);
        issues.forEach(issue -> {
            Div item = new Div();
            item.getStyle().set("font-size", "13px").set("padding", "2px 0 2px 8px");
            boolean critical = "CRITICAL".equals(issue.getSeverity()) || "BLOCKING".equals(issue.getSeverity());
            if (critical) item.getStyle().set("border-left", "3px solid var(--lumo-error-color)");
            Span sev = new Span("[" + issue.getSeverity() + "] ");
            sev.getStyle().set("font-weight", "600")
                    .set("color", critical ? "var(--lumo-error-color)" : "var(--lumo-warning-color)");
            item.add(sev, new Span(issue.getDescription()));
            section.add(item);
        });
        return section;
    }

    private VerticalLayout buildDecisionsSection(List<OwnerDecision> decisions) {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(true);
        section.setSpacing(true);
        section.getStyle()
                .set("background", "var(--lumo-warning-color-10pct)")
                .set("border", "1px solid var(--lumo-warning-color)")
                .set("border-radius", "var(--lumo-border-radius-m)")
                .set("margin-top", "8px");
        H4 title = new H4("Owner Decisions Required");
        title.getStyle().set("margin", "0 0 4px 0");
        section.add(title);
        decisions.forEach(d -> {
            Div item = new Div();
            Paragraph q = new Paragraph(d.getQuestion());
            q.getStyle().set("font-weight", "600").set("margin", "0");
            item.add(q);
            if (d.getOptions() != null && !d.getOptions().isEmpty()) {
                UnorderedList opts = new UnorderedList();
                d.getOptions().forEach(o -> opts.add(new ListItem(o)));
                opts.getStyle().set("margin", "4px 0").set("font-size", "13px");
                item.add(opts);
            }
            section.add(item);
        });
        return section;
    }

    // -------------------------------------------------------------------------
    // Approval actions
    // -------------------------------------------------------------------------

    private void onApprove(UUID runId) {
        try {
            agentOrchestrator.approveRun(runId);
            if (approvalBar != null) approvalBar.setVisible(false);
            if (headerStatus != null) headerStatus.setText("Approved");
            Notification.show("Run approved — next agent is starting",
                    3000, Notification.Position.BOTTOM_CENTER)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        } catch (Exception e) {
            log.error("Failed to approve run {}", runId, e);
            Notification.show("Approval failed: " + e.getMessage(),
                    4000, Notification.Position.BOTTOM_CENTER)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private void openRejectDialog(UUID runId) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Reject Agent Run");
        dialog.setWidth("400px");

        TextArea reasonField = new TextArea("Reason for rejection");
        reasonField.setWidthFull();
        reasonField.setMinHeight("80px");
        reasonField.setPlaceholder("Describe what needs to be corrected…");
        dialog.add(reasonField);

        Button confirmBtn = new Button("Confirm Rejection", e -> {
            if (reasonField.isEmpty()) {
                reasonField.setInvalid(true);
                return;
            }
            agentOrchestrator.rejectRun(runId, reasonField.getValue());
            if (approvalBar != null) approvalBar.setVisible(false);
            if (headerStatus != null) headerStatus.setText("Rejected");
            dialog.close();
            Notification.show("Run rejected", 3000, Notification.Position.BOTTOM_CENTER)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        });
        confirmBtn.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(new Button("Cancel", e -> dialog.close()), confirmBtn);
        dialog.open();
    }

    // -------------------------------------------------------------------------
    // Formatters
    // -------------------------------------------------------------------------

    private String buildTimingText(AgentRun run) {
        StringBuilder sb = new StringBuilder();
        if (run.getStartedAt() != null) {
            sb.append("Started ").append(run.getStartedAt().format(TIME_FMT));
        }
        if (run.getCompletedAt() != null) {
            sb.append(" · Finished ").append(run.getCompletedAt().format(TIME_FMT));
        }
        if (run.getDurationSeconds() != null) {
            long s = run.getDurationSeconds();
            sb.append(" · Took ").append(s < 60 ? s + "s" : (s / 60) + "m " + (s % 60) + "s");
        }
        if (run.getRetryCount() != null && run.getRetryCount() > 0) {
            sb.append(" · Retries: ").append(run.getRetryCount());
        }
        return sb.isEmpty() ? "Not started yet" : sb.toString();
    }

    private String resolveStatusText(AgentRunStatus status) {
        return switch (status) {
            case PENDING -> "Pending";
            case RUNNING -> "Running";
            case COMPLETED -> "Completed";
            case FAILED -> "Failed";
            case AWAITING_APPROVAL -> "Review Needed";
            case APPROVED -> "Approved";
            case REJECTED -> "Rejected";
            case TERMINATED -> "Stopped";
        };
    }

    private String resolveStatusColor(AgentRunStatus status) {
        return switch (status) {
            case RUNNING -> "#1565C0";
            case COMPLETED, APPROVED -> "#2E7D32";
            case AWAITING_APPROVAL -> "#E65100";
            case FAILED, REJECTED -> "#C62828";
            default -> "#616161";
        };
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
