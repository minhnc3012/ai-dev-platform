package com.aidevplatform.ui.components;

import com.aidevplatform.domain.entity.AgentReport;
import com.aidevplatform.domain.enums.AgentRunStatus;
import com.aidevplatform.domain.model.Deliverable;
import com.aidevplatform.domain.model.Issue;
import com.aidevplatform.domain.model.OwnerDecision;
import com.aidevplatform.service.AgentOrchestrator;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.progressbar.ProgressBarVariant;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.theme.lumo.LumoUtility;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Card component rendering a structured agent report with optional approve/reject actions.
 */
public class ReportCard extends VerticalLayout {

    private final AgentOrchestrator agentOrchestrator;

    public ReportCard(AgentReport report, AgentOrchestrator agentOrchestrator) {
        this.agentOrchestrator = agentOrchestrator;
        setPadding(true);
        setSpacing(true);
        getStyle()
                .set("border", "1px solid var(--lumo-contrast-10pct)")
                .set("border-radius", "var(--lumo-border-radius-m)");

        // Header: agent name + confidence score
        HorizontalLayout header = buildHeader(report);
        add(header);

        // Summary section
        Paragraph summary = new Paragraph(report.getSummary());
        summary.getStyle().set("font-size", "14px");
        add(summary);

        // Deliverables section
        if (report.getDeliverables() != null && !report.getDeliverables().isEmpty()) {
            add(buildDeliverablesSection(report.getDeliverables()));
        }

        // Issues section
        if (report.getIssuesFound() != null && !report.getIssuesFound().isEmpty()) {
            add(buildIssuesSection(report.getIssuesFound()));
        }

        // Owner decisions section (highlighted if present)
        if (report.getOwnerDecisionsNeeded() != null && !report.getOwnerDecisionsNeeded().isEmpty()) {
            add(buildDecisionsSection(report.getOwnerDecisionsNeeded()));
        }

        // Approval buttons (only when run is awaiting approval)
        if (report.getRun() != null && report.getRun().getStatus() == AgentRunStatus.AWAITING_APPROVAL) {
            add(buildApprovalButtons(report.getRun().getId()));
        }
    }

    private HorizontalLayout buildHeader(AgentReport report) {
        Span agentNameSpan = new Span(resolveDisplayName(
                report.getRun() != null ? report.getRun().getAgentName() : "unknown"));
        agentNameSpan.getStyle().set("font-weight", "500").set("font-size", "15px");

        HorizontalLayout header = new HorizontalLayout(agentNameSpan);
        header.setAlignItems(FlexComponent.Alignment.CENTER);

        if (report.getConfidenceScore() != null) {
            Span confidenceLabel = new Span(report.getConfidenceScore() + "%");
            confidenceLabel.getStyle().set("font-size", "13px");

            ProgressBar confidenceBar = new ProgressBar();
            confidenceBar.setValue(report.getConfidenceScore().doubleValue() / 100.0);
            confidenceBar.setWidth("120px");

            // Color code confidence level
            if (report.getConfidenceScore().compareTo(new BigDecimal("70")) < 0) {
                confidenceBar.addThemeVariants(ProgressBarVariant.LUMO_ERROR);
            } else if (report.getConfidenceScore().compareTo(new BigDecimal("85")) < 0) {
                confidenceBar.addThemeVariants(ProgressBarVariant.LUMO_CONTRAST);
            }

            header.add(confidenceBar, confidenceLabel);

            if (report.getConfidenceReason() != null) {
                Span reasonLabel = new Span(report.getConfidenceReason());
                reasonLabel.getStyle()
                        .set("font-size", "11px")
                        .set("color", "var(--lumo-secondary-text-color)");
                header.add(reasonLabel);
            }
        }
        return header;
    }

    private VerticalLayout buildDeliverablesSection(List<Deliverable> deliverables) {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(false);
        section.setSpacing(false);

        H4 sectionTitle = new H4("Deliverables (" + deliverables.size() + ")");
        sectionTitle.addClassNames(LumoUtility.Margin.Bottom.XSMALL);
        section.add(sectionTitle);

        deliverables.forEach(d -> {
            Div item = new Div();
            Span type = new Span("[" + d.getType() + "] ");
            type.getStyle().set("color", "var(--lumo-primary-color)").set("font-size", "12px");
            Span name = new Span(d.getName());
            name.getStyle().set("font-weight", "500");
            Span desc = new Span(" — " + (d.getDescription() != null ? d.getDescription() : ""));
            desc.getStyle().set("font-size", "12px").set("color", "var(--lumo-secondary-text-color)");
            item.add(type, name, desc);
            section.add(item);
        });
        return section;
    }

    private VerticalLayout buildIssuesSection(List<Issue> issues) {
        VerticalLayout section = new VerticalLayout();
        section.setPadding(false);
        section.setSpacing(false);

        H4 sectionTitle = new H4("Issues Found (" + issues.size() + ")");
        sectionTitle.addClassNames(LumoUtility.Margin.Bottom.XSMALL);
        section.add(sectionTitle);

        issues.forEach(issue -> {
            Div item = new Div();
            boolean isBlocking = "BLOCKING".equals(issue.getSeverity());
            if (isBlocking) {
                item.addClassName("report-blocking-issue");
                item.getStyle().set("border-left", "3px solid var(--lumo-error-color)")
                        .set("padding-left", "8px");
            }
            Span severity = new Span("[" + issue.getSeverity() + "] ");
            severity.getStyle().set("font-weight", "500")
                    .set("color", isBlocking
                            ? "var(--lumo-error-color)"
                            : "var(--lumo-warning-color)")
                    .set("font-size", "12px");
            Span desc = new Span(issue.getDescription());
            desc.getStyle().set("font-size", "13px");
            item.add(severity, desc);
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
                .set("border-radius", "var(--lumo-border-radius-m)")
                .set("border", "1px solid var(--lumo-warning-color)");

        H4 sectionTitle = new H4("Owner Decisions Required (" + decisions.size() + ")");
        sectionTitle.addClassNames(LumoUtility.Margin.Bottom.XSMALL);
        section.add(sectionTitle);

        decisions.forEach(decision -> {
            Div item = new Div();
            Paragraph question = new Paragraph(decision.getQuestion());
            question.getStyle().set("font-weight", "500");
            Paragraph impact = new Paragraph("Impact: " + decision.getImpact());
            impact.getStyle().set("font-size", "12px").set("color", "var(--lumo-secondary-text-color)");
            item.add(question, impact);
            if (decision.getOptions() != null && !decision.getOptions().isEmpty()) {
                UnorderedList options = new UnorderedList();
                decision.getOptions().forEach(opt -> options.add(new ListItem(opt)));
                options.getStyle().set("font-size", "13px");
                item.add(options);
            }
            section.add(item);
        });
        return section;
    }

    private HorizontalLayout buildApprovalButtons(UUID runId) {
        Button approveBtn = new Button("Approve", e -> {
            agentOrchestrator.approveRun(runId);
            setVisible(false); // Hide after action
        });
        approveBtn.addThemeVariants(ButtonVariant.LUMO_SUCCESS, ButtonVariant.LUMO_PRIMARY);

        Button rejectBtn = new Button("Reject", e -> openRejectDialog(runId));
        rejectBtn.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);

        HorizontalLayout buttons = new HorizontalLayout(approveBtn, rejectBtn);
        buttons.addClassNames(LumoUtility.Margin.Top.MEDIUM);
        return buttons;
    }

    private void openRejectDialog(UUID runId) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Reject Agent Run");

        TextArea reasonField = new TextArea("Reason for rejection");
        reasonField.setWidthFull();
        reasonField.setMinHeight("80px");
        reasonField.setRequired(true);
        dialog.add(reasonField);

        Button confirmBtn = new Button("Confirm Rejection", e -> {
            if (reasonField.isEmpty()) {
                reasonField.setInvalid(true);
                return;
            }
            agentOrchestrator.rejectRun(runId, reasonField.getValue());
            dialog.close();
        });
        confirmBtn.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_PRIMARY);

        Button cancelBtn = new Button("Cancel", e -> dialog.close());
        dialog.getFooter().add(cancelBtn, confirmBtn);
        dialog.open();
    }

    private String resolveDisplayName(String agentName) {
        return switch (agentName) {
            case "pm" -> "PM Agent Report";
            case "architect" -> "Architect Report";
            case "dev" -> "Developer Report";
            case "qa" -> "QA Engineer Report";
            case "docs" -> "Technical Writer Report";
            default -> agentName + " Report";
        };
    }
}
