package com.aidevplatform.ui.views;

import com.aidevplatform.domain.entity.AgentReport;
import com.aidevplatform.domain.entity.AgentRun;
import com.aidevplatform.repository.AgentRunRepository;
import com.aidevplatform.service.AgentOrchestrator;
import com.aidevplatform.service.ReportService;
import com.aidevplatform.ui.MainLayout;
import com.aidevplatform.ui.components.ReportCard;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.html.H2;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.html.H3;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.html.Paragraph;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.router.*;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.theme.lumo.LumoUtility;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Displays all agent reports for a module with approve/reject controls.
 */
@PermitAll
@Route(value = "modules/:moduleId/reports", layout = MainLayout.class)
@PageTitle("Reports — AI Dev Platform")
@Slf4j
public class ReportView extends VerticalLayout implements BeforeEnterObserver {

    private final ReportService reportService;
    private final AgentOrchestrator agentOrchestrator;
    private final AgentRunRepository agentRunRepository;

    private UUID moduleId;

    public ReportView(ReportService reportService, AgentOrchestrator agentOrchestrator,
                      AgentRunRepository agentRunRepository) {
        this.reportService = reportService;
        this.agentOrchestrator = agentOrchestrator;
        this.agentRunRepository = agentRunRepository;
        setPadding(true);
        setSpacing(true);
        setSizeFull();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        String idParam = event.getRouteParameters().get("moduleId").orElse(null);
        if (idParam == null) {
            event.forwardTo(ProjectListView.class);
            return;
        }
        try {
            moduleId = UUID.fromString(idParam);
            buildUI();
        } catch (Exception e) {
            log.error("Failed to initialize report view: {}", idParam, e);
            event.forwardTo(ProjectListView.class);
        }
    }

    private void buildUI() {
        add(new H2("Agent Reports"));

        try {
            List<AgentRun> runs = reportService.findRunsForModule(moduleId);
            if (runs.isEmpty()) {
                Paragraph empty = new Paragraph("No agent runs found for this module.");
                empty.addClassNames(LumoUtility.TextColor.SECONDARY);
                add(empty);
                return;
            }

            runs.forEach(run -> {
                Optional<AgentReport> reportOpt = reportService.findByRunId(run.getId());
                if (reportOpt.isPresent()) {
                    ReportCard card = new ReportCard(reportOpt.get(), agentOrchestrator);
                    add(card);
                } else {
                    // Show placeholder for runs without a report yet
                    VerticalLayout placeholder = new VerticalLayout();
                    placeholder.setPadding(true);
                    placeholder.getStyle()
                            .set("border", "1px solid var(--lumo-contrast-10pct)")
                            .set("border-radius", "var(--lumo-border-radius-m)");
                    placeholder.add(new H3(resolveDisplayName(run.getAgentName())));
                    Paragraph status = new Paragraph("Status: " + run.getStatus().name());
                    status.addClassNames(LumoUtility.TextColor.SECONDARY);
                    placeholder.add(status);
                    add(placeholder);
                }
            });
        } catch (Exception e) {
            log.error("Failed to load reports for module: {}", moduleId, e);
            Paragraph error = new Paragraph("Failed to load reports: " + e.getMessage());
            error.getStyle().set("color", "var(--lumo-error-color)");
            add(error);
        }
    }

    private String resolveDisplayName(String agentName) {
        return switch (agentName) {
            case "pm" -> "PM Agent";
            case "architect" -> "Architect";
            case "dev" -> "Developer";
            case "qa" -> "QA Engineer";
            case "docs" -> "Technical Writer";
            default -> agentName;
        };
    }

    /**
     * Embedded report panel used within AgentMonitorView's tab sheet.
     */
    public static class ReportPanel extends VerticalLayout {

        public ReportPanel() {
            setPadding(false);
            setSpacing(true);
            add(new Paragraph("Reports will appear here as agents complete their work."));
        }
    }
}
