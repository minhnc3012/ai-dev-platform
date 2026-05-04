package com.aidevplatform.ui.views;

import com.aidevplatform.domain.entity.AgentTemplate;
import com.aidevplatform.domain.entity.Project;
import com.aidevplatform.domain.entity.WorkflowDefinition;
import com.aidevplatform.domain.model.WorkflowStage;
import com.aidevplatform.repository.AgentTemplateRepository;
import com.aidevplatform.repository.ProjectRepository;
import com.aidevplatform.service.WorkflowService;
import com.aidevplatform.ui.MainLayout;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.dnd.DragSource;
import com.vaadin.flow.component.dnd.DropEffect;
import com.vaadin.flow.component.dnd.DropTarget;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.*;
import jakarta.annotation.security.PermitAll;
import lombok.extern.slf4j.Slf4j;

import java.util.*;
import java.util.stream.Collectors;

@PermitAll
@Route(value = "projects/:projectId/workflows", layout = MainLayout.class)
@PageTitle("Workflow Builder — AI Dev Platform")
@Slf4j
public class WorkflowBuilderView extends VerticalLayout implements BeforeEnterObserver {

    // ─── Design tokens ────────────────────────────────────────────────────────
    private static final String BG_PAGE   = "#f5f4ef";
    private static final String BG_CARD   = "#ffffff";
    private static final String BG_COL    = "#fafaf7";
    private static final String BORDER    = "0.5px solid #e2e0d8";
    private static final String COL_W     = "210px";
    private static final String ACCENT    = "#4a6cf7";

    // ─── Services ─────────────────────────────────────────────────────────────
    private final WorkflowService workflowService;
    private final AgentTemplateRepository agentTemplateRepository;
    private final ProjectRepository projectRepository;

    // ─── Route state ──────────────────────────────────────────────────────────
    private UUID projectId;
    private Project project;
    private List<AgentTemplate> templates = new ArrayList<>();

    // ─── Editor state ─────────────────────────────────────────────────────────
    private WorkflowDefinition editingWf;
    private final List<ColumnState> columns = new ArrayList<>();

    // Workflow metadata fields (persisted across renders)
    private final TextField  wfNameField  = new TextField();
    private final TextField  wfDescField  = new TextField();
    private final Checkbox   wfPauseChk   = new Checkbox("Pause for review after each stage by default");

    // Canvas re-rendered on every state mutation
    private HorizontalLayout canvasRow;

    // ─── Constructor ──────────────────────────────────────────────────────────
    public WorkflowBuilderView(WorkflowService workflowService,
                                AgentTemplateRepository agentTemplateRepository,
                                ProjectRepository projectRepository) {
        this.workflowService = workflowService;
        this.agentTemplateRepository = agentTemplateRepository;
        this.projectRepository = projectRepository;
        setSizeFull();
        setPadding(false);
        setSpacing(false);
        getStyle().set("background", BG_PAGE);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        projectId = event.getRouteParameters().get("projectId").map(UUID::fromString).orElse(null);
        if (projectId == null) { event.rerouteTo(ProjectListView.class); return; }
        project = projectRepository.findById(projectId).orElse(null);
        if (project == null) { event.rerouteTo(ProjectListView.class); return; }
        templates = agentTemplateRepository.findByProjectId(projectId);
        showListView();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // LIST VIEW
    // ═════════════════════════════════════════════════════════════════════════

    private void showListView() {
        removeAll();
        setPadding(true);
        setSpacing(true);

        // Header
        HorizontalLayout header = new HorizontalLayout();
        header.setWidthFull();
        header.setAlignItems(Alignment.CENTER);
        header.setJustifyContentMode(JustifyContentMode.BETWEEN);

        VerticalLayout titleBlock = new VerticalLayout();
        titleBlock.setPadding(false);
        titleBlock.setSpacing(false);
        H2 title = new H2("Workflow Builder");
        title.getStyle().set("margin", "0");
        Paragraph sub = new Paragraph("Project: " + project.getName());
        sub.getStyle().set("color", "#73726c").set("margin", "0");
        titleBlock.add(title, sub);

        HorizontalLayout actions = new HorizontalLayout();
        Button backBtn = new Button("← Back");
        backBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        backBtn.addClickListener(e -> getUI().ifPresent(ui -> ui.navigate("projects/" + projectId)));

        Button createBtn = new Button("+ New Workflow");
        createBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        createBtn.addClickListener(e -> openEditor(null));

        actions.add(backBtn, createBtn);
        header.add(titleBlock, actions);
        add(header);

        // Workflow cards
        List<WorkflowDefinition> workflows = workflowService.listByProject(projectId);
        if (workflows.isEmpty()) {
            Paragraph empty = new Paragraph("No workflows yet. Click \"+ New Workflow\" to build one with the drag-and-drop editor.");
            empty.getStyle().set("color", "#73726c");
            add(empty);
        } else {
            for (WorkflowDefinition wf : workflows) add(buildWorkflowListCard(wf));
        }
    }

    private Div buildWorkflowListCard(WorkflowDefinition wf) {
        Div card = new Div();
        card.getStyle()
                .set("background", BG_CARD).set("border", BORDER).set("border-radius", "8px")
                .set("padding", "16px 20px").set("margin-bottom", "12px");

        HorizontalLayout row = new HorizontalLayout();
        row.setWidthFull();
        row.setAlignItems(Alignment.CENTER);
        row.setJustifyContentMode(JustifyContentMode.BETWEEN);

        VerticalLayout info = new VerticalLayout();
        info.setPadding(false);
        info.setSpacing(false);
        H3 name = new H3(wf.getName());
        name.getStyle().set("margin", "0");
        Span desc = new Span(wf.getDescription() != null ? wf.getDescription() : "");
        desc.getStyle().set("color", "#73726c").set("font-size", "0.85em");
        int stageCount = wf.getStages() != null ? wf.getStages().size() : 0;
        Span meta = new Span(stageCount + " stage(s) · " +
                (Boolean.TRUE.equals(wf.getDefaultPauseForReview()) ? "Review mode" : "Auto-run"));
        meta.getStyle().set("color", "#999").set("font-size", "0.78em");
        info.add(name, desc, meta);

        HorizontalLayout btns = new HorizontalLayout();
        Button editBtn = new Button("Edit in Builder");
        editBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_PRIMARY);
        editBtn.addClickListener(e -> openEditor(wf));

        Button deleteBtn = new Button("Delete");
        deleteBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
        deleteBtn.addClickListener(e -> {
            workflowService.delete(wf.getId());
            showListView();
            Notification.show("Workflow deleted").addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        });

        btns.add(editBtn, deleteBtn);
        row.add(info, btns);
        card.add(row);
        return card;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // EDITOR VIEW
    // ═════════════════════════════════════════════════════════════════════════

    private void openEditor(WorkflowDefinition wf) {
        this.editingWf = wf;
        this.columns.clear();
        this.templates = agentTemplateRepository.findByProjectId(projectId);

        // Populate metadata fields
        wfNameField.setValue(wf != null ? wf.getName() : "");
        wfDescField.setValue(wf != null && wf.getDescription() != null ? wf.getDescription() : "");
        wfPauseChk.setValue(wf == null || Boolean.TRUE.equals(wf.getDefaultPauseForReview()));

        // Convert existing stages → ColumnState list
        if (wf != null && wf.getStages() != null) {
            Map<String, AgentTemplate> tMap = templates.stream()
                    .collect(Collectors.toMap(t -> t.getId().toString(), t -> t));
            for (WorkflowStage stage : wf.getStages()) {
                ColumnState col = new ColumnState(
                        stage.getId() != null ? stage.getId() : UUID.randomUUID().toString(),
                        stage.getName() != null ? stage.getName() : "",
                        Boolean.TRUE.equals(stage.getPauseForReview()));
                if ("agent".equals(stage.getType()) && stage.getAgentTemplateId() != null) {
                    AgentTemplate t = tMap.get(stage.getAgentTemplateId());
                    if (t != null) col.agents.add(t);
                } else if ("parallel".equals(stage.getType()) && stage.getChildren() != null) {
                    for (WorkflowStage child : stage.getChildren()) {
                        if (child.getAgentTemplateId() != null) {
                            AgentTemplate t = tMap.get(child.getAgentTemplateId());
                            if (t != null) col.agents.add(t);
                        }
                    }
                }
                if (!col.agents.isEmpty()) columns.add(col);
            }
        }

        showEditorView();
    }

    private void showEditorView() {
        removeAll();
        setSizeFull();
        setPadding(false);
        setSpacing(false);

        add(buildTopBar());

        // Main area: left panel + scrollable canvas
        HorizontalLayout main = new HorizontalLayout();
        main.setSizeFull();
        main.setSpacing(false);
        main.setPadding(false);
        main.getStyle().set("overflow", "hidden").set("min-height", "0");

        main.add(buildLeftPanel());

        // Canvas wrapper (scrollable)
        Div canvasWrapper = new Div();
        canvasWrapper.getStyle()
                .set("flex", "1").set("overflow-x", "auto").set("overflow-y", "auto")
                .set("padding", "20px").set("background", BG_PAGE);

        canvasRow = new HorizontalLayout();
        canvasRow.setSpacing(false);
        canvasRow.setPadding(false);
        canvasRow.getStyle().set("align-items", "flex-start").set("min-height", "320px").set("gap", "0");
        renderCanvas();

        canvasWrapper.add(canvasRow);
        main.add(canvasWrapper);
        expand(main);
        add(main);
    }

    // ─── Top bar ──────────────────────────────────────────────────────────────

    private Div buildTopBar() {
        Div bar = new Div();
        bar.getStyle()
                .set("background", BG_CARD).set("border-bottom", BORDER)
                .set("padding", "10px 20px").set("display", "flex")
                .set("align-items", "center").set("gap", "10px").set("flex-shrink", "0")
                .set("flex-wrap", "wrap");

        Button backBtn = new Button("← Workflows");
        backBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
        backBtn.addClickListener(e -> showListView());

        Span sep = new Span("/");
        sep.getStyle().set("color", "#ccc");

        wfNameField.setPlaceholder("Workflow name…");
        wfNameField.getStyle().set("font-weight", "600");
        wfNameField.setWidth("200px");

        wfDescField.setPlaceholder("Description (optional)");
        wfDescField.getStyle().set("font-size", "0.85em");
        wfDescField.setWidth("240px");

        wfPauseChk.getStyle().set("font-size", "0.82em").set("white-space", "nowrap");

        Div spacer = new Div();
        spacer.getStyle().set("flex", "1");

        Button saveBtn = new Button(editingWf == null ? "Create Workflow" : "Save Workflow");
        saveBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveBtn.addClickListener(e -> saveWorkflow());

        bar.add(backBtn, sep, wfNameField, wfDescField, wfPauseChk, spacer, saveBtn);
        return bar;
    }

    // ─── Left panel ───────────────────────────────────────────────────────────

    private Div buildLeftPanel() {
        Div panel = new Div();
        panel.getStyle()
                .set("width", "250px").set("min-width", "250px").set("height", "100%")
                .set("background", BG_CARD).set("border-right", BORDER)
                .set("overflow-y", "auto").set("padding", "14px 12px")
                .set("display", "flex").set("flex-direction", "column").set("gap", "6px");

        Span heading = new Span("AGENT TEMPLATES");
        heading.getStyle()
                .set("font-size", "0.7em").set("font-weight", "700").set("letter-spacing", "0.08em")
                .set("color", "#999").set("display", "block").set("margin-bottom", "6px");
        panel.add(heading);

        if (templates.isEmpty()) {
            Paragraph empty = new Paragraph("No agent templates yet.");
            empty.getStyle().set("color", "#999").set("font-size", "0.82em");
            panel.add(empty);
        } else {
            for (AgentTemplate t : templates) panel.add(buildPaletteCard(t));
        }

        Div bottom = new Div();
        bottom.getStyle().set("margin-top", "auto").set("padding-top", "12px");
        Button manageBtn = new Button("⚙ Manage Templates");
        manageBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        manageBtn.setWidthFull();
        manageBtn.addClickListener(e -> getUI().ifPresent(ui ->
                ui.navigate("projects/" + projectId + "/agent-templates")));
        bottom.add(manageBtn);
        panel.add(bottom);

        return panel;
    }

    private Div buildPaletteCard(AgentTemplate template) {
        Div card = new Div();
        card.getStyle()
                .set("background", "#f4f6ff").set("border", "1px solid #d4daff")
                .set("border-radius", "7px").set("padding", "9px 11px")
                .set("cursor", "grab").set("user-select", "none")
                .set("display", "flex").set("align-items", "flex-start").set("gap", "8px")
                .set("transition", "box-shadow 0.15s");

        Span handle = new Span("⠿");
        handle.getStyle().set("color", "#b0b8dd").set("flex-shrink", "0").set("margin-top", "1px").set("font-size", "1.1em");

        Div info = new Div();
        Span name = new Span(template.getName());
        name.getStyle().set("font-weight", "600").set("font-size", "0.83em").set("display", "block");
        Span role = new Span(template.getRole() != null ? template.getRole() : "");
        role.getStyle().set("color", "#6675aa").set("font-size", "0.74em").set("display", "block");
        info.add(name, role);
        card.add(handle, info);

        DragSource<Div> ds = DragSource.create(card);
        ds.setDraggable(true);
        ds.setDragData(new DragPayload("palette", template, null));

        // Hover lift effect via JS
        card.getElement().addEventListener("mouseenter", e ->
                card.getStyle().set("box-shadow", "0 2px 8px rgba(74,108,247,0.15)"));
        card.getElement().addEventListener("mouseleave", e ->
                card.getStyle().remove("box-shadow"));

        return card;
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CANVAS
    // ═════════════════════════════════════════════════════════════════════════

    private void renderCanvas() {
        canvasRow.removeAll();

        for (int i = 0; i < columns.size(); i++) {
            if (i > 0) {
                Span arrow = new Span("→");
                arrow.getStyle()
                        .set("color", "#c8c4bb").set("font-size", "1.1em")
                        .set("align-self", "center").set("padding", "0 6px").set("flex-shrink", "0");
                canvasRow.add(arrow);
            }
            canvasRow.add(buildStageColumn(columns.get(i)));
        }

        // Add stage button
        Div addBtn = new Div();
        addBtn.getStyle()
                .set("display", "flex").set("align-items", "flex-start").set("padding-left", "14px");
        Button addStageBtn = new Button("+ Add Stage");
        addStageBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        addStageBtn.addClickListener(e -> {
            ColumnState col = new ColumnState(
                    UUID.randomUUID().toString(),
                    "Stage " + (columns.size() + 1),
                    wfPauseChk.getValue());
            columns.add(col);
            renderCanvas();
        });
        addBtn.add(addStageBtn);
        canvasRow.add(addBtn);
    }

    // ─── Stage column ─────────────────────────────────────────────────────────

    private Div buildStageColumn(ColumnState col) {
        Div column = new Div();
        column.getStyle()
                .set("background", BG_COL).set("border", BORDER).set("border-radius", "10px")
                .set("width", COL_W).set("min-width", COL_W).set("flex-shrink", "0")
                .set("display", "flex").set("flex-direction", "column")
                .set("overflow", "hidden").set("box-shadow", "0 1px 4px rgba(0,0,0,0.04)");

        // ── Column header (drag source for reorder) ──
        Div header = buildColumnHeader(col, column);
        column.add(header);

        // ── Parallel badge ──
        if (col.agents.size() > 1) {
            Span badge = new Span("⟳ parallel (" + col.agents.size() + ")");
            badge.getStyle()
                    .set("background", "#e6f9ee").set("color", "#1e7e3e")
                    .set("font-size", "0.68em").set("font-weight", "600")
                    .set("padding", "2px 8px").set("border-radius", "0")
                    .set("border-bottom", "0.5px solid #b8eece").set("display", "block")
                    .set("text-align", "center");
            column.add(badge);
        }

        // ── Agent cards body ──
        Div body = new Div();
        body.getStyle()
                .set("padding", "8px").set("display", "flex")
                .set("flex-direction", "column").set("gap", "6px").set("min-height", "80px");

        for (AgentTemplate agent : col.agents) {
            body.add(buildAgentCard(agent, col));
        }

        // ── Drop zone ──
        Div dropZone = new Div();
        dropZone.getStyle()
                .set("border", "1.5px dashed #d8d5cc").set("border-radius", "6px")
                .set("padding", "8px 6px").set("text-align", "center")
                .set("color", "#bbb").set("font-size", "0.76em")
                .set("min-height", "36px").set("display", "flex")
                .set("align-items", "center").set("justify-content", "center")
                .set("transition", "all 0.15s").set("cursor", "default");
        dropZone.add(new Span(col.agents.isEmpty() ? "Drop an agent here" : "+ Drop to add parallel"));

        setupAgentDropTarget(dropZone, col);
        body.add(dropZone);
        column.add(body);

        // ── Column-level drop target for column reorder ──
        setupColumnDropTarget(column, col);

        return column;
    }

    private Div buildColumnHeader(ColumnState col, Div column) {
        Div header = new Div();
        header.getStyle()
                .set("padding", "8px 10px").set("border-bottom", BORDER)
                .set("display", "flex").set("align-items", "center").set("gap", "5px")
                .set("background", "#f0efe9").set("cursor", "grab");

        Span handle = new Span("⠿");
        handle.getStyle().set("color", "#bbb").set("flex-shrink", "0").set("font-size", "1.1em");

        TextField nameField = new TextField();
        nameField.setValue(col.name);
        nameField.setWidthFull();
        nameField.getStyle().set("font-size", "0.82em").set("font-weight", "600");
        nameField.addValueChangeListener(e -> col.name = e.getValue());

        Checkbox pauseChk = new Checkbox();
        pauseChk.setValue(col.pauseForReview);
        pauseChk.getElement().setAttribute("title", "Pause for review after this stage");
        pauseChk.getStyle().set("flex-shrink", "0");
        pauseChk.addValueChangeListener(e -> col.pauseForReview = e.getValue());

        Button deleteBtn = new Button("×");
        deleteBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);
        deleteBtn.getStyle().set("flex-shrink", "0").set("min-width", "0").set("padding", "0 4px");
        deleteBtn.addClickListener(e -> {
            columns.remove(col);
            renderCanvas();
        });

        header.add(handle, nameField, pauseChk, deleteBtn);

        // Make header the drag source for column reorder
        DragSource<Div> ds = DragSource.create(header);
        ds.setDraggable(true);
        ds.setDragData(new DragPayload("column", null, col.id));

        return header;
    }

    private Div buildAgentCard(AgentTemplate template, ColumnState col) {
        Div card = new Div();
        card.getStyle()
                .set("background", "#eef1ff").set("border", "1px solid #c4cef7")
                .set("border-radius", "7px").set("padding", "7px 9px")
                .set("cursor", "grab").set("user-select", "none")
                .set("display", "flex").set("align-items", "flex-start").set("gap", "6px")
                .set("transition", "box-shadow 0.15s");

        Div info = new Div();
        info.getStyle().set("flex", "1").set("min-width", "0");
        Span name = new Span(template.getName());
        name.getStyle().set("font-weight", "600").set("font-size", "0.82em")
                .set("display", "block").set("white-space", "nowrap")
                .set("overflow", "hidden").set("text-overflow", "ellipsis");
        Span role = new Span(template.getRole() != null ? template.getRole() : "");
        role.getStyle().set("color", "#5a6a9a").set("font-size", "0.73em")
                .set("white-space", "nowrap").set("overflow", "hidden").set("text-overflow", "ellipsis");
        info.add(name, role);

        Button removeBtn = new Button("×");
        removeBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        removeBtn.getStyle().set("flex-shrink", "0").set("min-width", "0")
                .set("padding", "0 3px").set("height", "20px").set("font-size", "14px");
        removeBtn.addClickListener(e -> {
            col.agents.remove(template);
            if (col.agents.isEmpty()) columns.remove(col);
            renderCanvas();
        });

        card.add(info, removeBtn);

        DragSource<Div> ds = DragSource.create(card);
        ds.setDraggable(true);
        ds.setDragData(new DragPayload("agent", template, col.id));

        card.getElement().addEventListener("mouseenter", e ->
                card.getStyle().set("box-shadow", "0 2px 6px rgba(74,108,247,0.18)"));
        card.getElement().addEventListener("mouseleave", e ->
                card.getStyle().remove("box-shadow"));

        return card;
    }

    // ─── Drop target setup ────────────────────────────────────────────────────

    private void setupAgentDropTarget(Div dropZone, ColumnState targetCol) {
        DropTarget<Div> dt = DropTarget.create(dropZone);
        dt.setActive(true);
        dt.setDropEffect(DropEffect.MOVE);

        dropZone.getElement().addEventListener("dragenter", e ->
                dropZone.getStyle().set("background", "#dde6ff").set("border-color", ACCENT)
                        .set("color", ACCENT));
        dropZone.getElement().addEventListener("dragleave", e ->
                dropZone.getStyle().remove("background").set("border-color", "#d8d5cc")
                        .set("color", "#bbb"));

        dt.addDropListener(event -> {
            dropZone.getStyle().remove("background").set("border-color", "#d8d5cc").set("color", "#bbb");
            event.getDragData().ifPresent(raw -> {
                if (!(raw instanceof DragPayload payload)) return;
                if (!"palette".equals(payload.type()) && !"agent".equals(payload.type())) return;
                AgentTemplate template = payload.template();
                if (template == null) return;

                // Remove from source column when moving (not from palette)
                if ("agent".equals(payload.type()) && payload.sourceColId() != null
                        && !payload.sourceColId().equals(targetCol.id)) {
                    columns.stream()
                            .filter(c -> c.id.equals(payload.sourceColId()))
                            .findFirst()
                            .ifPresent(src -> src.agents.remove(template));
                    columns.removeIf(c -> c.agents.isEmpty());
                }

                // Add to target if not already present
                if (!targetCol.agents.contains(template)) {
                    targetCol.agents.add(template);
                }
                renderCanvas();
            });
        });
    }

    private void setupColumnDropTarget(Div columnDiv, ColumnState targetCol) {
        DropTarget<Div> dt = DropTarget.create(columnDiv);
        dt.setActive(true);
        dt.setDropEffect(DropEffect.MOVE);

        columnDiv.getElement().addEventListener("dragenter", e ->
                columnDiv.getStyle().set("outline", "2px solid " + ACCENT));
        columnDiv.getElement().addEventListener("dragleave", e ->
                columnDiv.getStyle().remove("outline"));

        dt.addDropListener(event -> {
            columnDiv.getStyle().remove("outline");
            event.getDragData().ifPresent(raw -> {
                if (!(raw instanceof DragPayload payload)) return;
                if (!"column".equals(payload.type())) return;
                String draggedId = payload.sourceColId();
                if (draggedId == null || draggedId.equals(targetCol.id)) return;

                ColumnState dragged = columns.stream()
                        .filter(c -> c.id.equals(draggedId))
                        .findFirst().orElse(null);
                if (dragged == null) return;

                int fromIdx = columns.indexOf(dragged);
                int toIdx   = columns.indexOf(targetCol);
                columns.remove(fromIdx);
                columns.add(toIdx, dragged);
                renderCanvas();
            });
        });
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SAVE
    // ═════════════════════════════════════════════════════════════════════════

    private void saveWorkflow() {
        if (wfNameField.isEmpty()) {
            Notification.show("Workflow name is required").addThemeVariants(NotificationVariant.LUMO_ERROR);
            wfNameField.focus();
            return;
        }
        List<ColumnState> nonEmpty = columns.stream()
                .filter(c -> !c.agents.isEmpty()).collect(Collectors.toList());
        if (nonEmpty.isEmpty()) {
            Notification.show("Add at least one agent stage").addThemeVariants(NotificationVariant.LUMO_ERROR);
            return;
        }

        List<WorkflowStage> stages = new ArrayList<>();
        for (ColumnState col : nonEmpty) {
            if (col.agents.size() == 1) {
                AgentTemplate t = col.agents.get(0);
                stages.add(WorkflowStage.builder()
                        .id(col.id).type("agent")
                        .name(col.name.isBlank() ? t.getName() : col.name)
                        .agentTemplateId(t.getId().toString())
                        .pauseForReview(col.pauseForReview ? Boolean.TRUE : null)
                        .build());
            } else {
                List<WorkflowStage> children = col.agents.stream().map(t ->
                        WorkflowStage.builder()
                                .id(UUID.randomUUID().toString()).type("agent")
                                .name(t.getName()).agentTemplateId(t.getId().toString())
                                .build()
                ).collect(Collectors.toList());
                stages.add(WorkflowStage.builder()
                        .id(col.id).type("parallel")
                        .name(col.name.isBlank() ? "Parallel Group" : col.name)
                        .pauseForReview(col.pauseForReview ? Boolean.TRUE : null)
                        .children(children)
                        .build());
            }
        }

        WorkflowDefinition wf = editingWf != null ? editingWf : new WorkflowDefinition();
        wf.setProject(project);
        wf.setName(wfNameField.getValue().trim());
        wf.setDescription(wfDescField.getValue().isBlank() ? null : wfDescField.getValue());
        wf.setDefaultPauseForReview(wfPauseChk.getValue());
        wf.setIsTemplate(false);
        wf.setStages(stages);
        workflowService.save(wf);

        Notification.show("Workflow saved ✓").addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        showListView();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // INNER CLASSES
    // ═════════════════════════════════════════════════════════════════════════

    private static class ColumnState {
        String id;
        String name;
        boolean pauseForReview;
        final List<AgentTemplate> agents = new ArrayList<>();

        ColumnState(String id, String name, boolean pauseForReview) {
            this.id = id;
            this.name = name;
            this.pauseForReview = pauseForReview;
        }
    }

    /**
     * @param type        "palette" | "agent" | "column"
     * @param template    AgentTemplate being dragged (palette/agent types)
     * @param sourceColId column ID — source column (agent type) or column being moved (column type)
     */
    record DragPayload(String type, AgentTemplate template, String sourceColId) {}
}
