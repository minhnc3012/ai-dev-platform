package com.aidevplatform.ui.views;

import com.aidevplatform.api.dto.AgentEventDto;
import com.aidevplatform.domain.entity.AgentReport;
import com.aidevplatform.domain.entity.AgentRun;
import com.aidevplatform.domain.entity.Module;
import com.aidevplatform.domain.enums.AgentRunStatus;
import com.aidevplatform.domain.model.Deliverable;
import com.aidevplatform.domain.model.Issue;
import com.aidevplatform.domain.model.OwnerDecision;
import com.aidevplatform.repository.AgentEventRepository;
import com.aidevplatform.repository.AgentRunRepository;
import com.aidevplatform.service.AgentOrchestrator;
import com.aidevplatform.service.FileStorageService;
import com.aidevplatform.service.ModuleService;
import com.aidevplatform.service.ReportService;
import com.aidevplatform.service.UiEventBroadcaster;
import com.aidevplatform.ui.MainLayout;
import com.aidevplatform.ui.components.AgentLogPanel;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.progressbar.ProgressBar;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.TabSheet;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.router.*;
import jakarta.annotation.security.PermitAll;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Agent Monitor — redesigned to match agent_monitor_mockup.html.
 *
 * Layout:
 *   Header: module name · summary badges (running/done/waiting) · Resume · Back
 *   TabSheet:
 *     ① Agent Monitor — vertical list of agent rows with pulse dot, status, live message, elapsed
 *     ② Event Log     — dark terminal with all events from all agents
 *     ③ Report        — structured report card for the selected agent + Approve/Reject
 */
@PermitAll
@Route(value = "modules/:moduleId/monitor", layout = MainLayout.class)
@PageTitle("Agent Monitor — AI Dev Platform")
@Slf4j
public class AgentMonitorView extends VerticalLayout implements BeforeEnterObserver {

    // ── Pipeline order ────────────────────────────────────────────────────────
    private static final List<String> PIPELINE = List.of("pm", "architect", "dev", "qa", "docs");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ── Design tokens (matching mockup) ──────────────────────────────────────
    private static final String BG_PAGE  = "#f5f4ef";
    private static final String BG_CARD  = "#ffffff";
    private static final String BORDER   = "0.5px solid #e2e0d8";
    private static final String TEXT_PRI = "#1a1a18";
    private static final String TEXT_SEC = "#73726c";

    // ── Services ──────────────────────────────────────────────────────────────
    private final ModuleService moduleService;
    private final AgentRunRepository agentRunRepository;
    private final AgentEventRepository agentEventRepository;
    private final ReportService reportService;
    private final AgentOrchestrator agentOrchestrator;
    private final FileStorageService fileStorageService;
    private final UiEventBroadcaster uiEventBroadcaster;

    // ── State ─────────────────────────────────────────────────────────────────
    private UUID moduleId;
    private UUID broadcastRegistrationId;
    private Registration pollRegistration;
    private String selectedAgent; // agent whose report is shown in the Report tab

    // ── Live-updatable row parts ──────────────────────────────────────────────
    /** Holds references to the sub-components inside one agent row that need live updates. */
    private static class RowRefs {
        Div root;
        Span dot;
        Span statusBadge;
        Span taskText;
        Span timeLabel;
        Div progressWrap;
    }
    private final Map<String, RowRefs> rowMap = new LinkedHashMap<>();

    // ── Panel / tab refs ──────────────────────────────────────────────────────
    private Span summaryRunning;
    private Span summaryDone;
    private Span summaryWaiting;
    private AgentLogPanel logPanel;
    private VerticalLayout reportPane; // content in the Report tab
    private Tab reportTabRef;          // kept to switch to Report tab programmatically
    private TabSheet mainTabs;

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────────────────

    public AgentMonitorView(ModuleService moduleService,
                             AgentRunRepository agentRunRepository,
                             AgentEventRepository agentEventRepository,
                             ReportService reportService,
                             AgentOrchestrator agentOrchestrator,
                             FileStorageService fileStorageService,
                             UiEventBroadcaster uiEventBroadcaster) {
        this.moduleService = moduleService;
        this.agentRunRepository = agentRunRepository;
        this.agentEventRepository = agentEventRepository;
        this.reportService = reportService;
        this.agentOrchestrator = agentOrchestrator;
        this.fileStorageService = fileStorageService;
        this.uiEventBroadcaster = uiEventBroadcaster;

        setSizeFull();
        setPadding(false);
        setSpacing(false);
        getStyle().set("background", BG_PAGE);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    protected void onAttach(AttachEvent event) {
        super.onAttach(event);
        UI ui = event.getUI();
        if (moduleId != null && broadcastRegistrationId == null) {
            broadcastRegistrationId = uiEventBroadcaster.registerListener(
                    moduleId, ui, this::onAgentEvent);
        }
        // Poll every 3 s as a guaranteed delivery channel — push (@Push WebSocket) delivers
        // updates immediately when the connection is healthy; the poll catches anything the
        // push missed (transport hiccup, session-lock contention, etc.).
        ui.setPollInterval(3000);
        pollRegistration = ui.addPollListener(e -> {
            if (moduleId != null) refreshRunStates();
        });
    }

    @Override
    protected void onDetach(DetachEvent event) {
        if (broadcastRegistrationId != null) {
            uiEventBroadcaster.unregisterListener(broadcastRegistrationId);
            broadcastRegistrationId = null;
        }
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
        }
        event.getUI().setPollInterval(-1);
        super.onDetach(event);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        String idParam = event.getRouteParameters().get("moduleId").orElse(null);
        if (idParam == null) { event.forwardTo(ProjectListView.class); return; }
        try {
            moduleId = UUID.fromString(idParam);
        } catch (IllegalArgumentException e) {
            event.forwardTo(ProjectListView.class);
            return;
        }

        try {
            buildUI();
            loadCurrentState();
            getUI().ifPresent(ui -> {
                if (broadcastRegistrationId == null) {
                    broadcastRegistrationId = uiEventBroadcaster.registerListener(
                            moduleId, ui, this::onAgentEvent);
                }
            });
        } catch (Exception e) {
            log.error("Monitor failed to load for module {}: {}", moduleId, e.getMessage(), e);
            removeAll();
            VerticalLayout err = new VerticalLayout();
            err.setPadding(true);
            err.add(new H3("Monitor failed to load"));
            err.add(new Paragraph("Error: " + e.getMessage()));
            Button back = new Button("← Back to Projects",
                    ev -> getUI().ifPresent(ui -> ui.navigate(ProjectListView.class)));
            back.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
            err.add(back);
            add(err);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI construction
    // ─────────────────────────────────────────────────────────────────────────

    private void buildUI() {
        removeAll();
        rowMap.clear();
        selectedAgent = null;

        Module module = moduleService.findByIdWithDetails(moduleId);

        // ── Header ────────────────────────────────────────────────────────────
        add(buildHeaderBar(module));

        // ── TabSheet ──────────────────────────────────────────────────────────
        mainTabs = new TabSheet();
        mainTabs.setSizeFull();
        mainTabs.getStyle()
                .set("--lumo-tab-selected-color", "#534AB7")
                .set("flex", "1");

        // Tab 1: Agent Monitor — vertical agent rows
        mainTabs.add("Agent Monitor", buildMonitorTab(module));

        // Tab 2: Event Log — dark terminal (all agents)
        logPanel = new AgentLogPanel();
        logPanel.getStyle().set("max-height", "none").set("border-radius", "0");
        Div logWrapper = new Div(logPanel);
        logWrapper.setSizeFull();
        logWrapper.getStyle().set("padding", "12px").set("box-sizing", "border-box");
        mainTabs.add("Event Log", logWrapper);

        // Tab 3: Report — report card for selected agent
        reportPane = new VerticalLayout();
        reportPane.setSizeFull();
        reportPane.setPadding(true);
        reportPane.setSpacing(true);
        reportPane.getStyle().set("overflow-y", "auto");
        showReportPlaceholder();
        reportTabRef = mainTabs.add("Report", reportPane);

        add(mainTabs);
        setFlexGrow(1, mainTabs);
    }

    // ── Header bar ────────────────────────────────────────────────────────────

    private HorizontalLayout buildHeaderBar(Module module) {
        HorizontalLayout header = new HorizontalLayout();
        header.setWidthFull();
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.setPadding(true);
        header.setSpacing(false);
        header.getStyle()
                .set("background", BG_CARD)
                .set("border-bottom", BORDER)
                .set("flex-shrink", "0")
                .set("gap", "8px");

        Span title = new Span(module.getName());
        title.getStyle()
                .set("font-size", "15px").set("font-weight", "500").set("color", TEXT_PRI);

        // Summary count badges
        summaryRunning = makeSummaryBadge("0 running", "running");
        summaryDone    = makeSummaryBadge("0 done",    "done");
        summaryWaiting = makeSummaryBadge("0 waiting", "waiting");

        HorizontalLayout badgeRow = new HorizontalLayout(summaryRunning, summaryDone, summaryWaiting);
        badgeRow.setSpacing(false);
        badgeRow.setPadding(false);
        badgeRow.getStyle().set("gap", "6px");

        Span spacer = new Span();
        spacer.getStyle().set("flex", "1");

        Button resumeBtn = new Button("⟳ Resume", e -> resumePipeline());
        resumeBtn.addThemeVariants(ButtonVariant.LUMO_CONTRAST, ButtonVariant.LUMO_SMALL);
        resumeBtn.getElement().setProperty("title",
                "Re-dispatch a stuck running agent or re-show the approval prompt");

        Button reRunBtn = new Button("↻ Re-run Pipeline", e -> reRunPipeline());
        reRunBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY, ButtonVariant.LUMO_SMALL);
        reRunBtn.getElement().setProperty("title",
                "Stop all agents and re-run the entire pipeline from the beginning");

        Button backBtn = new Button("← Back", e ->
                getUI().ifPresent(ui -> ui.navigate(ProjectDetailView.class,
                        new RouteParameters("projectId",
                                module.getProject().getId().toString()))));
        backBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);

        header.add(title, badgeRow, spacer, resumeBtn, reRunBtn, backBtn);
        return header;
    }

    private Span makeSummaryBadge(String text, String type) {
        Span badge = new Span(text);
        badge.getStyle()
                .set("display", "inline-flex").set("align-items", "center")
                .set("font-size", "11px").set("padding", "3px 10px")
                .set("border-radius", "999px").set("font-weight", "500");
        styleSummaryBadge(badge, type);
        return badge;
    }

    private void styleSummaryBadge(Span badge, String type) {
        switch (type) {
            case "running" -> badge.getStyle().set("background", "#E6F1FB").set("color", "#0C447C");
            case "done"    -> badge.getStyle().set("background", "#EAF3DE").set("color", "#27500A");
            case "waiting" -> badge.getStyle().set("background", "#FAEEDA").set("color", "#633806");
            case "error"   -> badge.getStyle().set("background", "#FCEBEB").set("color", "#791F1F");
            default        -> badge.getStyle().set("background", "#F1EFE8").set("color", "#5F5E5A");
        }
    }

    // ── Monitor tab ───────────────────────────────────────────────────────────

    private VerticalLayout buildMonitorTab(Module module) {
        VerticalLayout pane = new VerticalLayout();
        pane.setSizeFull();
        pane.setPadding(true);
        pane.setSpacing(false);
        pane.getStyle().set("overflow-y", "auto").set("gap", "6px");

        List<String> active = module.getProject().getAiConfig() != null
                ? module.getProject().getAiConfig().getActiveAgents()
                : PIPELINE;

        PIPELINE.stream()
                .filter(active::contains)
                .forEach(name -> pane.add(buildAgentRow(name)));

        return pane;
    }

    private Div buildAgentRow(String agentName) {
        // Pulse dot
        Span dot = new Span();
        dot.getStyle()
                .set("width", "8px").set("height", "8px").set("min-width", "8px")
                .set("border-radius", "50%")
                .set("background", "#B4B2A9")    // default: gray/idle
                .set("display", "inline-block")
                .set("flex-shrink", "0")
                .set("margin-top", "3px");

        // Name label
        Span nameLbl = new Span(resolveDisplayName(agentName));
        nameLbl.getStyle()
                .set("font-size", "13px").set("font-weight", "500")
                .set("color", TEXT_PRI).set("width", "120px").set("flex-shrink", "0");

        // Inline status badge
        Span badge = new Span("Idle");
        badge.getStyle()
                .set("font-size", "11px").set("padding", "2px 8px")
                .set("border-radius", "999px").set("font-weight", "500")
                .set("flex-shrink", "0")
                .set("background", "#F1EFE8").set("color", "#5F5E5A");

        // Current task / last message text
        Span taskText = new Span("Waiting to start");
        taskText.getStyle()
                .set("font-size", "12px").set("color", TEXT_SEC)
                .set("flex", "1").set("overflow", "hidden")
                .set("text-overflow", "ellipsis").set("white-space", "nowrap");

        // Elapsed time
        Span timeLbl = new Span("—");
        timeLbl.getStyle()
                .set("font-size", "11px").set("color", "#a09e96")
                .set("font-family", "monospace").set("min-width", "52px")
                .set("text-align", "right").set("flex-shrink", "0");

        HorizontalLayout rowContent = new HorizontalLayout(dot, nameLbl, badge, taskText, timeLbl);
        rowContent.setAlignItems(FlexComponent.Alignment.BASELINE);
        rowContent.setWidthFull();
        rowContent.setPadding(false);
        rowContent.setSpacing(false);
        rowContent.getStyle().set("gap", "12px");

        // Progress bar — indeterminate, visible only when RUNNING
        ProgressBar progressBar = new ProgressBar();
        progressBar.setIndeterminate(true);
        progressBar.setWidthFull();
        progressBar.getStyle().set("height", "3px");

        Div progressWrap = new Div(progressBar);
        progressWrap.setWidthFull();
        progressWrap.getStyle().set("margin-top", "6px");
        progressWrap.setVisible(false);

        // Row container card
        Div row = new Div(rowContent, progressWrap);
        row.getStyle()
                .set("background", BG_CARD)
                .set("border", BORDER)
                .set("border-radius", "8px")
                .set("padding", "14px 16px")
                .set("cursor", "pointer")
                .set("transition", "box-shadow .15s")
                .set("min-height", "40px");

        // Hover highlight via JS
        row.getElement().executeJs(
                "this.addEventListener('mouseenter', () => this.style.boxShadow='0 1px 6px rgba(0,0,0,.08)');" +
                "this.addEventListener('mouseleave', () => this.style.boxShadow='none');");

        final String nameCapture = agentName;
        row.addClickListener(e -> onAgentRowClicked(nameCapture));

        // Store refs for live updates
        RowRefs refs = new RowRefs();
        refs.root         = row;
        refs.dot          = dot;
        refs.statusBadge  = badge;
        refs.taskText     = taskText;
        refs.timeLabel    = timeLbl;
        refs.progressWrap = progressWrap;
        rowMap.put(agentName, refs);

        return row;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // State loading
    // ─────────────────────────────────────────────────────────────────────────

    private void loadCurrentState() {
        try {
            // Use findAllByModuleIdWithReportAndRun to eagerly load all relationships
            List<AgentRun> runs = agentRunRepository.findAllByModuleIdWithReportAndRun(moduleId);

            // Update each agent row from persisted state
            runs.forEach(run -> {
                RowRefs refs = rowMap.get(run.getAgentName());
                if (refs != null) updateRowFromRun(refs, run);
            });

            updateSummaryBadges(runs);

            // Load all events for the global event log
            List<UUID> runIds = runs.stream().map(AgentRun::getId).collect(Collectors.toList());
            if (!runIds.isEmpty()) {
                List<AgentEventDto> allEvents = agentEventRepository
                        .findByRunIdInWithRunOrderByCreatedAtAsc(runIds)
                        .stream().map(this::mapEventToDto).toList();
                logPanel.loadHistory(allEvents);
            }

            // Auto-select: AWAITING_APPROVAL > RUNNING > last COMPLETED/APPROVED
            AgentRun toSelect = runs.stream()
                    .filter(r -> r.getStatus() == AgentRunStatus.AWAITING_APPROVAL)
                    .findFirst()
                    .or(() -> runs.stream()
                            .filter(r -> r.getStatus() == AgentRunStatus.RUNNING)
                            .findFirst())
                    .or(() -> runs.stream()
                            .filter(r -> r.getStatus() == AgentRunStatus.COMPLETED
                                    || r.getStatus() == AgentRunStatus.APPROVED)
                            .reduce((a, b) -> b))
                    .orElse(null);

            if (toSelect != null) {
                selectedAgent = toSelect.getAgentName();
                log.info("loadCurrentState: auto-selected agent={}, runId={}, status={}",
                        toSelect.getAgentName(), toSelect.getId(), toSelect.getStatus());
                refreshReportPane(toSelect);
                // Highlight selected row
                highlightRow(toSelect.getAgentName());
                // Auto-switch to Report tab if awaiting approval
                if (toSelect.getStatus() == AgentRunStatus.AWAITING_APPROVAL) {
                    mainTabs.setSelectedTab(reportTabRef);
                }
            } else {
                log.info("loadCurrentState: no agent to select, showing placeholder");
                showReportPlaceholder();
            }

        } catch (Exception e) {
            log.error("Failed to load state for module {}: {}", moduleId, e.getMessage(), e);
        }
    }

    /**
     * Lightweight poll-driven refresh: updates row indicators and summary badges
     * from the DB without touching the event log or report pane (no flicker).
     * Called every 3 s by the UI poll listener as a fallback delivery channel
     * for updates that the @Push WebSocket may have missed.
     */
    private void refreshRunStates() {
        try {
            List<AgentRun> runs = agentRunRepository.findAllByModuleIdWithReportAndRun(moduleId);
            runs.forEach(run -> {
                RowRefs refs = rowMap.get(run.getAgentName());
                if (refs != null) updateRowFromRun(refs, run);
            });
            updateSummaryBadges(runs);

            // Stop polling once the pipeline is fully finished — no further updates expected.
            boolean pipelineActive = runs.stream().anyMatch(r ->
                    r.getStatus() == AgentRunStatus.RUNNING
                    || r.getStatus() == AgentRunStatus.PENDING
                    || r.getStatus() == AgentRunStatus.AWAITING_APPROVAL);
            if (!pipelineActive && pollRegistration != null) {
                pollRegistration.remove();
                pollRegistration = null;
                UI.getCurrent().setPollInterval(-1);
                log.debug("Pipeline complete — stopped UI polling for module {}", moduleId);
            }
        } catch (Exception e) {
            log.warn("Poll refresh failed for module {}: {}", moduleId, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Row update helpers
    // ─────────────────────────────────────────────────────────────────────────

    private void updateRowFromRun(RowRefs refs, AgentRun run) {
        AgentRunStatus status = run.getStatus();

        // Dot color
        refs.dot.getStyle().set("background", resolveDotColor(status));

        // Badge text + colors
        String badgeText = resolveBadgeText(status);
        refs.statusBadge.setText(badgeText);
        applyInlineBadgeStyle(refs.statusBadge, status);

        // Task text — use last event message stored, or a status description
        String task = resolveTaskText(run);
        refs.taskText.setText(task);

        // Time label
        refs.timeLabel.setText(resolveTimeLabel(run));

        // Progress bar
        boolean isRunning = status == AgentRunStatus.RUNNING;
        refs.progressWrap.setVisible(isRunning);

        // Row border highlight for AWAITING_APPROVAL
        if (status == AgentRunStatus.AWAITING_APPROVAL) {
            refs.root.getStyle().set("border-left", "3px solid #BA7517");
        } else if (status == AgentRunStatus.RUNNING) {
            refs.root.getStyle().set("border-left", "3px solid #378ADD");
        } else if (status == AgentRunStatus.COMPLETED || status == AgentRunStatus.APPROVED) {
            refs.root.getStyle().set("border-left", "3px solid #639922");
        } else if (status == AgentRunStatus.FAILED || status == AgentRunStatus.REJECTED) {
            refs.root.getStyle().set("border-left", "3px solid #E24B4A");
        } else {
            refs.root.getStyle().remove("border-left");
        }
    }

    private void updateRowFromEvent(RowRefs refs, AgentEventDto event, AgentRunStatus status) {
        refs.dot.getStyle().set("background", resolveDotColor(status));
        applyInlineBadgeStyle(refs.statusBadge, status);
        refs.statusBadge.setText(resolveBadgeText(status));

        if (event.getMessage() != null && !event.getMessage().isBlank()) {
            String truncated = event.getMessage().length() > 120
                    ? event.getMessage().substring(0, 120) + "…"
                    : event.getMessage();
            refs.taskText.setText(truncated);
        }

        boolean isRunning = status == AgentRunStatus.RUNNING;
        refs.progressWrap.setVisible(isRunning);

        if (status == AgentRunStatus.AWAITING_APPROVAL) {
            refs.root.getStyle().set("border-left", "3px solid #BA7517");
        } else if (isRunning) {
            refs.root.getStyle().set("border-left", "3px solid #378ADD");
        } else if (status == AgentRunStatus.COMPLETED || status == AgentRunStatus.APPROVED) {
            refs.root.getStyle().set("border-left", "3px solid #639922");
            refs.progressWrap.setVisible(false);
        } else if (status == AgentRunStatus.FAILED) {
            refs.root.getStyle().set("border-left", "3px solid #E24B4A");
            refs.progressWrap.setVisible(false);
        }
    }

    private void updateSummaryBadges(List<AgentRun> runs) {
        long running = runs.stream()
                .filter(r -> r.getStatus() == AgentRunStatus.RUNNING).count();
        long done = runs.stream()
                .filter(r -> r.getStatus() == AgentRunStatus.COMPLETED
                        || r.getStatus() == AgentRunStatus.APPROVED).count();
        long waiting = runs.stream()
                .filter(r -> r.getStatus() == AgentRunStatus.PENDING
                        || r.getStatus() == AgentRunStatus.AWAITING_APPROVAL).count();

        summaryRunning.setText(running + " running");
        summaryDone.setText(done + " done");
        summaryWaiting.setText(waiting + " waiting");

        summaryRunning.setVisible(running > 0);
        summaryDone.setVisible(done > 0);
        summaryWaiting.setVisible(waiting > 0);
    }

    private void highlightRow(String agentName) {
        rowMap.forEach((name, refs) -> {
            boolean selected = name.equals(agentName);
            refs.root.getStyle().set("outline",
                    selected ? "2px solid #534AB7" : "none");
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Report pane
    // ─────────────────────────────────────────────────────────────────────────

    private void showReportPlaceholder() {
        reportPane.removeAll();
        VerticalLayout ph = new VerticalLayout();
        ph.setSizeFull();
        ph.setAlignItems(Alignment.CENTER);
        ph.setJustifyContentMode(JustifyContentMode.CENTER);
        Span icon = new Span("👈");
        icon.getStyle().set("font-size", "40px");
        Span msg = new Span("Click an agent row to view its report");
        msg.getStyle().set("font-size", "13px").set("color", TEXT_SEC);
        ph.add(icon, msg);
        reportPane.add(ph);
    }

    private void refreshReportPane(AgentRun run) {
        reportPane.removeAll();

        // Refresh run status from DB to get current status
        Optional<AgentRun> currentRunOpt = agentRunRepository.findById(run.getId());
        if (currentRunOpt.isEmpty()) {
            log.warn("Run {} not found when refreshing report pane", run.getId());
            return;
        }
        AgentRun currentRun = currentRunOpt.get();

        Optional<AgentReport> reportOpt = reportService.findByRunId(run.getId());

        if (reportOpt.isEmpty()) {
            // No report yet — show status-appropriate placeholder
            VerticalLayout ph = new VerticalLayout();
            ph.setAlignItems(Alignment.CENTER);
            ph.setJustifyContentMode(JustifyContentMode.CENTER);
            ph.setSizeFull();
            String msg = switch (currentRun.getStatus()) {
                case PENDING  -> "This agent has not started yet.";
                case RUNNING  -> "Agent is running — report will appear when done.";
                default       -> "No report available for this run.";
            };
            Span s = new Span(msg);
            s.getStyle().set("color", TEXT_SEC).set("font-size", "13px");
            ph.add(s);
            reportPane.add(ph);
            return;
        }

        AgentReport report = reportOpt.get();
        // Log status for debugging
        log.info("Refreshing report for run {} (agent: {}, status: {}, has report: true, reportId: {})",
                currentRun.getId(), currentRun.getAgentName(), currentRun.getStatus(), report.getId());
        reportPane.add(buildReportCard(currentRun, report));
    }

    /**
     * Builds the styled report card matching agent_monitor_mockup.html §Report panel.
     */
    private Div buildReportCard(AgentRun run, AgentReport report) {
        Div card = new Div();
        card.getStyle()
                .set("background", BG_CARD)
                .set("border", BORDER)
                .set("border-radius", "12px")
                .set("padding", "16px 18px");

        // ── Header: name + confidence bar ─────────────────────────────────────
        HorizontalLayout header = new HorizontalLayout();
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.setWidthFull();
        header.setSpacing(false);
        header.getStyle().set("gap", "12px").set("margin-bottom", "12px");

        Span agentTitle = new Span(resolveDisplayName(run.getAgentName()) + " — report");
        agentTitle.getStyle().set("font-size", "15px").set("font-weight", "500").set("flex", "1");

        if (report.getConfidenceScore() != null) {
            double pct = report.getConfidenceScore().doubleValue();

            Div barWrap = new Div();
            barWrap.getStyle()
                    .set("width", "120px").set("height", "6px")
                    .set("background", "#e2e0d8").set("border-radius", "3px");

            Div barFill = new Div();
            barFill.getStyle()
                    .set("height", "6px").set("border-radius", "3px")
                    .set("width", (int) pct + "%")
                    .set("background", pct >= 70 ? "#639922" : "#BA7517");
            barWrap.add(barFill);

            Span pctLbl = new Span((int) pct + "%");
            pctLbl.getStyle()
                    .set("font-size", "12px").set("font-weight", "500")
                    .set("color", pct >= 70 ? "#27500A" : "#633806");

            header.add(agentTitle, barWrap, pctLbl);
        } else {
            header.add(agentTitle);
        }
        card.add(header);

        // ── Summary ───────────────────────────────────────────────────────────
        if (report.getSummary() != null && !report.getSummary().isBlank()) {
            Paragraph summary = new Paragraph(report.getSummary());
            summary.getStyle()
                    .set("font-size", "13px").set("color", "#3d3d3a")
                    .set("line-height", "1.6").set("margin-bottom", "14px");
            card.add(summary);
        }

        // ── Deliverables ──────────────────────────────────────────────────────
        if (report.getDeliverables() != null && !report.getDeliverables().isEmpty()) {
            card.add(buildSectionLabel("Deliverables"));
            Div delSection = new Div();
            delSection.getStyle().set("margin-bottom", "14px");
            for (Deliverable d : report.getDeliverables()) {
                Div item = new Div();
                item.getStyle()
                        .set("display", "flex").set("align-items", "flex-start").set("gap", "8px")
                        .set("font-size", "12px").set("padding", "5px 0")
                        .set("border-bottom", "0.5px solid #f0efe9");

                Div icon = new Div();
                icon.getStyle()
                        .set("width", "14px").set("height", "14px").set("border-radius", "3px")
                        .set("flex-shrink", "0").set("margin-top", "1px")
                        .set("background", resolveDeliverableIconColor(d.getType()));

                Span nameSpan = new Span(d.getName()
                        + (d.getDescription() != null ? " — " + d.getDescription() : ""));
                nameSpan.getStyle().set("color", TEXT_PRI);
                item.add(icon, nameSpan);
                delSection.add(item);
            }
            card.add(delSection);
        }

        // ── Issues ────────────────────────────────────────────────────────────
        if (report.getIssuesFound() != null && !report.getIssuesFound().isEmpty()) {
            card.add(buildSectionLabel("Issues Found (" + report.getIssuesFound().size() + ")"));
            Div issueSection = new Div();
            issueSection.getStyle().set("margin-bottom", "14px");
            for (Issue issue : report.getIssuesFound()) {
                boolean blocking = "CRITICAL".equals(issue.getSeverity())
                        || "BLOCKING".equals(issue.getSeverity());
                Div item = new Div();
                item.getStyle()
                        .set("font-size", "12px").set("padding", "4px 4px 4px 10px")
                        .set("border-left", "3px solid " + (blocking ? "#E24B4A" : "#BA7517"))
                        .set("margin-bottom", "4px");
                Span sev = new Span("[" + issue.getSeverity() + "] ");
                sev.getStyle().set("font-weight", "600")
                        .set("color", blocking ? "#791F1F" : "#633806");
                item.add(sev, new Span(issue.getDescription()));
                issueSection.add(item);
            }
            card.add(issueSection);
        }

        // ── Owner decisions ───────────────────────────────────────────────────
        if (report.getOwnerDecisionsNeeded() != null && !report.getOwnerDecisionsNeeded().isEmpty()) {
            card.add(buildSectionLabel("Owner decisions needed"));
            Div decSection = new Div();
            decSection.getStyle().set("margin-bottom", "14px");
            for (OwnerDecision d : report.getOwnerDecisionsNeeded()) {
                Div item = new Div();
                item.getStyle()
                        .set("display", "flex").set("align-items", "flex-start").set("gap", "8px")
                        .set("font-size", "12px").set("padding", "5px 10px")
                        .set("border-left", "3px solid #BA7517")
                        .set("border-radius", "0 4px 4px 0")
                        .set("background", "#fdf8f0").set("margin-bottom", "4px");
                item.add(new Span(d.getQuestion()));
                decSection.add(item);
            }
            card.add(decSection);
        }

        // ── Confidence reason ─────────────────────────────────────────────────
        if (report.getConfidenceReason() != null && !report.getConfidenceReason().isBlank()) {
            card.add(buildSectionLabel("Confidence reason"));
            Paragraph reason = new Paragraph(report.getConfidenceReason());
            reason.getStyle()
                    .set("font-size", "12px").set("color", TEXT_SEC)
                    .set("line-height", "1.6").set("margin-bottom", "14px");
            card.add(reason);
        }

        // ── Approve / Reject (only when AWAITING_APPROVAL) ────────────────────
        log.info("buildReportCard: runId={}, agent={}, status={}, showApproveBtn={}",
                run.getId(), run.getAgentName(), run.getStatus(),
                run.getStatus() == AgentRunStatus.AWAITING_APPROVAL);

        // Show status-appropriate message
        if (run.getStatus() == AgentRunStatus.AWAITING_APPROVAL) {
            log.info("Adding approve/reject buttons for run {}", run.getId());
            card.add(buildApprovalRow(run.getId()));
        } else {
            // Show clear status message based on current state
            Span statusMsg = new Span(resolveStatusMessage(run));
            statusMsg.getStyle().set("font-size", "12px").set("color", "var(--lumo-secondary-text-color)")
                    .set("margin-top", "10px").set("font-style", "italic");
            card.add(statusMsg);
        }

        return card;
    }

    private Span buildSectionLabel(String text) {
        Span label = new Span(text);
        label.getStyle()
                .set("font-size", "11px").set("font-weight", "500")
                .set("color", TEXT_SEC).set("text-transform", "uppercase")
                .set("letter-spacing", ".06em")
                .set("display", "block").set("margin-bottom", "7px");
        return label;
    }

    /**
     * Resolves a user-friendly status message for the report card.
     * Provides clear feedback about why Approve/Reject buttons are not shown.
     */
    private String resolveStatusMessage(AgentRun run) {
        return switch (run.getStatus()) {
            case APPROVED -> "✓ Approved and completed";
            case COMPLETED -> "✓ Completed, awaiting next agent";
            case RUNNING -> "● Running";
            case PENDING -> "◼ Waiting to start";
            case FAILED -> "✗ Failed: " + (run.getErrorMessage() != null ? run.getErrorMessage() : "Unknown error");
            case REJECTED -> "✗ Rejected (retry pending)";
            case TERMINATED -> "⊘ Manually stopped";
            case AWAITING_APPROVAL -> "⊘ Awaiting your approval";
        };
    }

    private HorizontalLayout buildApprovalRow(UUID runId) {
        HorizontalLayout row = new HorizontalLayout();
        row.setSpacing(false);
        row.getStyle()
                .set("gap", "8px").set("margin-top", "14px")
                .set("padding-top", "12px").set("border-top", "0.5px solid #e2e0d8");

        Button approveBtn = new Button("Approve — run next agent", e -> onApprove(runId));
        approveBtn.getStyle()
                .set("background", "#EAF3DE").set("color", "#27500A")
                .set("border", "1px solid #97C459").set("border-radius", "6px")
                .set("font-size", "13px").set("font-weight", "500").set("cursor", "pointer");

        Button rejectBtn = new Button("Reject", e -> openRejectDialog(runId));
        rejectBtn.getStyle()
                .set("background", "transparent").set("color", "#791F1F")
                .set("border", "1px solid #F09595").set("border-radius", "6px")
                .set("font-size", "13px").set("font-weight", "500").set("cursor", "pointer");

        row.add(approveBtn, rejectBtn);
        return row;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Event handling (called inside UI.access by UiEventBroadcaster)
    // ─────────────────────────────────────────────────────────────────────────

    private void onAgentEvent(AgentEventDto event) {
        String agentName = event.getAgentName();
        AgentRunStatus newStatus = resolveStatusFromEvent(event.getEventType());

        // 1. Update the agent row
        RowRefs refs = rowMap.get(agentName);
        if (refs != null) {
            updateRowFromEvent(refs, event, newStatus);
        }

        // 2. Append to global event log
        logPanel.appendEvent(event);

        // 3. Refresh summary badges from DB
        try {
            List<AgentRun> runs = agentRunRepository.findAllByModuleIdWithReportAndRun(moduleId);
            updateSummaryBadges(runs);
        } catch (Exception ignored) {}

        // 4. Handle AWAITING_APPROVAL: switch to Report tab, build report, notify
        if ("AWAITING_APPROVAL".equals(event.getEventType())) {
            try {
                // Use findAllByModuleIdWithReportAndRun to load report relationship
                List<AgentRun> runs = agentRunRepository.findAllByModuleIdWithReportAndRun(moduleId);
                runs.stream()
                        .filter(r -> r.getAgentName().equals(agentName)
                                && r.getStatus() == AgentRunStatus.AWAITING_APPROVAL)
                        .findFirst()
                        .ifPresent(run -> {
                            selectedAgent = agentName;
                            log.debug("AWAITING_APPROVAL event for {}: runId={}", agentName, run.getId());
                            refreshReportPane(run);
                            highlightRow(agentName);
                            mainTabs.setSelectedTab(reportTabRef);
                        });
            } catch (Exception ex) {
                log.error("Failed to load report for AWAITING_APPROVAL: agent={}", agentName, ex);
            }

            Notification n = Notification.show(
                    "⚠️  " + resolveDisplayName(agentName) + " completed — review and approve to continue",
                    8000, Notification.Position.TOP_CENTER);
            n.addThemeVariants(NotificationVariant.LUMO_WARNING);
        }

        // 5. If the currently shown report is for this agent and it just completed, refresh it
        if (agentName.equals(selectedAgent)
                && ("COMPLETED".equals(event.getEventType())
                || "AWAITING_APPROVAL".equals(event.getEventType()))) {
            try {
                // Use findAllByModuleIdWithReportAndRun to load report relationship
                List<AgentRun> runs = agentRunRepository.findAllByModuleIdWithReportAndRun(moduleId);
                runs.stream().filter(r -> r.getAgentName().equals(agentName)).findFirst()
                        .ifPresent(this::refreshReportPane);
            } catch (Exception ignored) {}
        }

        // 6. MODULE_COMPLETE: show success notification
        if ("MODULE_COMPLETE".equals(event.getEventType())) {
            Notification n = Notification.show("✓ All agents completed successfully",
                    5000, Notification.Position.TOP_CENTER);
            n.addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        }
    }

    private void onAgentRowClicked(String agentName) {
        log.info("onAgentRowClicked: agent={}, selectedAgent={}, moduleId={}",
                agentName, selectedAgent, moduleId);
        highlightRow(agentName);
        selectedAgent = agentName;

        try {
            // Reload run directly from DB to get the most current status
            List<AgentRun> runs = agentRunRepository.findAllByModuleIdWithReportAndRun(moduleId);
            var runOpt = runs.stream().filter(r -> r.getAgentName().equals(agentName)).findFirst();

            if (runOpt.isPresent()) {
                var run = runOpt.get();
                log.info("Agent row clicked: agent={}, runId={}, status={}, hasReport={}",
                        agentName, run.getId(), run.getStatus(), run.getReport() != null);

                // Force reload from DB to ensure we have the latest status
                Optional<AgentRun> freshRunOpt = agentRunRepository.findById(run.getId());
                if (freshRunOpt.isPresent()) {
                    refreshReportPane(freshRunOpt.get());
                } else {
                    log.warn("Run {} not found after reload", run.getId());
                    showReportPlaceholder();
                }
                mainTabs.setSelectedTab(reportTabRef);
            } else {
                log.warn("Agent {} not found in runs for module {}", agentName, moduleId);
                showReportPlaceholder();
                mainTabs.setSelectedTab(reportTabRef);
            }
        } catch (Exception e) {
            log.error("Failed to load run for agent {}: {}", agentName, e.getMessage());
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Approval actions
    // ─────────────────────────────────────────────────────────────────────────

    private void onApprove(UUID runId) {
        try {
            agentOrchestrator.approveRun(runId);
            // Reload the report pane to hide the approval buttons
            try {
                // Use findAllByModuleIdWithReportAndRun to load report relationship
                List<AgentRun> runs = agentRunRepository.findAllByModuleIdWithReportAndRun(moduleId);
                runs.stream().filter(r -> r.getId().equals(runId)).findFirst()
                        .ifPresent(this::refreshReportPane);
            } catch (Exception ignored) {}
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

        TextArea reason = new TextArea("Reason for rejection");
        reason.setWidthFull();
        reason.setMinHeight("80px");
        reason.setPlaceholder("Describe what needs to be corrected…");
        dialog.add(reason);

        Button confirm = new Button("Confirm Rejection", e -> {
            if (reason.isEmpty()) { reason.setInvalid(true); return; }
            agentOrchestrator.rejectRun(runId, reason.getValue());
            try {
                // Use findAllByModuleIdWithReportAndRun to load report relationship
                List<AgentRun> runs = agentRunRepository.findAllByModuleIdWithReportAndRun(moduleId);
                runs.stream().filter(r -> r.getId().equals(runId)).findFirst()
                        .ifPresent(this::refreshReportPane);
            } catch (Exception ignored) {}
            dialog.close();
            Notification.show("Run rejected", 3000, Notification.Position.BOTTOM_CENTER)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        });
        confirm.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_PRIMARY);
        dialog.getFooter().add(new Button("Cancel", e -> dialog.close()), confirm);
        dialog.open();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Resume
    // ─────────────────────────────────────────────────────────────────────────

    private void resumePipeline() {
        try {
            String result = agentOrchestrator.resumePipeline(moduleId);
            String msg = switch (result.split(":")[0]) {
                case "AWAITING_APPROVAL" -> "Approval prompt refreshed for " + result.split(":")[1];
                case "REDISPATCHED"      -> "Re-dispatched agent: " + result.split(":")[1];
                case "TRIGGERED_NEXT"   -> "Next agent has been triggered";
                default                  -> result;
            };
            Notification.show(msg, 4000, Notification.Position.BOTTOM_CENTER)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        } catch (Exception e) {
            log.error("Resume pipeline failed for module {}: {}", moduleId, e.getMessage(), e);
            Notification.show("Resume failed: " + e.getMessage(),
                    5000, Notification.Position.BOTTOM_CENTER)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    /**
     * Re-runs the entire pipeline from the beginning.
     * Stops all running agents, resets all agent runs to PENDING,
     * and triggers the first agent again.
     * This is useful when the pipeline completed but some agents failed.
     */
    private void reRunPipeline() {
        Module module = moduleService.findByIdWithDetails(moduleId);
        com.aidevplatform.domain.enums.ModuleStatus status = module.getStatus();
        if (status == com.aidevplatform.domain.enums.ModuleStatus.DRAFT) {
            Notification.show("Cannot re-run a DRAFT module",
                    3000, Notification.Position.BOTTOM_CENTER)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
            return;
        }

        Dialog confirmDialog = new Dialog();
        confirmDialog.setHeaderTitle("Re-run Pipeline");
        confirmDialog.add(new Paragraph(
                "This will stop all running agents and re-run the entire pipeline from the beginning. " +
                "All generated output will be overwritten."));

        Button confirmBtn = new Button("Re-run", e -> {
            confirmDialog.close();
            try {
                moduleService.stopAndResetPipeline(moduleId);
                Notification.show("Pipeline re-run started",
                        3000, Notification.Position.BOTTOM_CENTER)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
                loadCurrentState(); // Refresh UI
            } catch (Exception ex) {
                log.error("Failed to re-run pipeline", ex);
                Notification.show("Failed to re-run: " + ex.getMessage(),
                        5000, Notification.Position.BOTTOM_CENTER)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        confirmBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button cancelBtn = new Button("Cancel", e -> confirmDialog.close());

        HorizontalLayout actions = new HorizontalLayout(confirmBtn, cancelBtn);
        actions.setSpacing(true);
        actions.setPadding(false);
        confirmDialog.add(new Paragraph(), actions);

        confirmDialog.open();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Formatters & resolvers
    // ─────────────────────────────────────────────────────────────────────────

    private String resolveDotColor(AgentRunStatus status) {
        return switch (status) {
            case RUNNING           -> "#378ADD";   // blue
            case COMPLETED, APPROVED -> "#639922"; // green
            case AWAITING_APPROVAL -> "#BA7517";   // amber
            case FAILED, REJECTED  -> "#E24B4A";   // red
            default                -> "#B4B2A9";   // gray
        };
    }

    private String resolveBadgeText(AgentRunStatus status) {
        return switch (status) {
            case PENDING           -> "Waiting";
            case RUNNING           -> "Running";
            case COMPLETED         -> "Done";
            case APPROVED          -> "Approved";
            case AWAITING_APPROVAL -> "Review needed";
            case FAILED            -> "Failed";
            case REJECTED          -> "Rejected";
            case TERMINATED        -> "Stopped";
        };
    }

    private void applyInlineBadgeStyle(Span badge, AgentRunStatus status) {
        switch (status) {
            case RUNNING           -> badge.getStyle().set("background", "#E6F1FB").set("color", "#0C447C");
            case COMPLETED, APPROVED -> badge.getStyle().set("background", "#EAF3DE").set("color", "#27500A");
            case AWAITING_APPROVAL -> badge.getStyle().set("background", "#FAEEDA").set("color", "#633806");
            case FAILED, REJECTED  -> badge.getStyle().set("background", "#FCEBEB").set("color", "#791F1F");
            default                -> badge.getStyle().set("background", "#F1EFE8").set("color", "#5F5E5A");
        }
    }

    private AgentRunStatus resolveStatusFromEvent(String eventType) {
        return switch (eventType) {
            case "STARTED"           -> AgentRunStatus.RUNNING;
            case "COMPLETED"         -> AgentRunStatus.COMPLETED;
            case "AWAITING_APPROVAL" -> AgentRunStatus.AWAITING_APPROVAL;
            case "ERROR"             -> AgentRunStatus.FAILED;
            default                  -> AgentRunStatus.RUNNING;
        };
    }

    private String resolveTaskText(AgentRun run) {
        return switch (run.getStatus()) {
            case PENDING           -> "Waiting to start";
            case RUNNING           -> "Running…";
            case COMPLETED         -> "Completed successfully";
            case APPROVED          -> "Approved — passed to next agent";
            case AWAITING_APPROVAL -> "Waiting for your review and approval";
            case FAILED            -> run.getErrorMessage() != null
                    ? "Failed: " + run.getErrorMessage().substring(0,
                    Math.min(80, run.getErrorMessage().length()))
                    : "Failed";
            case REJECTED          -> "Rejected — retry in progress";
            case TERMINATED        -> "Manually stopped";
        };
    }

    private String resolveTimeLabel(AgentRun run) {
        if (run.getDurationSeconds() != null && run.getDurationSeconds() > 0) {
            long s = run.getDurationSeconds();
            return s < 60 ? s + "s" : (s / 60) + "m " + (s % 60) + "s";
        }
        if (run.getStartedAt() != null && run.getStatus() == AgentRunStatus.RUNNING) {
            long elapsed = Duration.between(run.getStartedAt(), LocalDateTime.now()).getSeconds();
            return elapsed < 60 ? elapsed + "s" : (elapsed / 60) + "m " + (elapsed % 60) + "s";
        }
        return "—";
    }

    private String resolveDeliverableIconColor(String type) {
        if (type == null) return "#E6F1FB";
        return switch (type.toLowerCase()) {
            case "code", "impl" -> "#EEEDFE";
            case "test"         -> "#E1F5EE";
            default             -> "#E6F1FB";
        };
    }

    private String resolveDisplayName(String name) {
        return switch (name) {
            case "pm"        -> "PM Agent";
            case "architect" -> "Architect";
            case "dev"       -> "Dev Agent";
            case "qa"        -> "QA Agent";
            case "docs"      -> "Docs Agent";
            default          -> name;
        };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helper — map AgentEvent entity → DTO
    // ─────────────────────────────────────────────────────────────────────────

    private AgentEventDto mapEventToDto(com.aidevplatform.domain.entity.AgentEvent event) {
        return AgentEventDto.builder()
                .eventId(event.getId())
                .runId(event.getRun().getId())
                .agentName(event.getRun().getAgentName())
                .eventType(event.getEventType().name())
                .message(event.getMessage())
                .severity(event.getSeverity().name())
                .payload(event.getPayload())
                .timestamp(event.getCreatedAt())
                .build();
    }
}
