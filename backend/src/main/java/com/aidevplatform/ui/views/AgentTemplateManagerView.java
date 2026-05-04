package com.aidevplatform.ui.views;

import com.aidevplatform.domain.entity.AgentTemplate;
import com.aidevplatform.domain.entity.Project;
import com.aidevplatform.repository.AgentTemplateRepository;
import com.aidevplatform.repository.ProjectRepository;
import com.aidevplatform.ui.MainLayout;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.*;
import jakarta.annotation.security.PermitAll;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;

@PermitAll
@Route(value = "projects/:projectId/agent-templates", layout = MainLayout.class)
@PageTitle("Agent Templates — AI Dev Platform")
@Slf4j
public class AgentTemplateManagerView extends VerticalLayout implements BeforeEnterObserver {

    private static final String BG_PAGE = "#f5f4ef";
    private static final String BG_CARD = "#ffffff";
    private static final String BORDER = "0.5px solid #e2e0d8";

    private final AgentTemplateRepository agentTemplateRepository;
    private final ProjectRepository projectRepository;

    private UUID projectId;
    private Project project;
    private Grid<AgentTemplate> grid;

    public AgentTemplateManagerView(AgentTemplateRepository agentTemplateRepository,
                                     ProjectRepository projectRepository) {
        this.agentTemplateRepository = agentTemplateRepository;
        this.projectRepository = projectRepository;
        setSizeFull();
        setPadding(true);
        setSpacing(true);
        getStyle().set("background", BG_PAGE);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        projectId = event.getRouteParameters().get("projectId")
                .map(UUID::fromString).orElse(null);
        if (projectId == null) {
            event.rerouteTo(ProjectListView.class);
            return;
        }
        project = projectRepository.findById(projectId).orElse(null);
        if (project == null) {
            event.rerouteTo(ProjectListView.class);
            return;
        }
        buildUi();
    }

    private void buildUi() {
        removeAll();

        // Header
        HorizontalLayout header = new HorizontalLayout();
        header.setWidthFull();
        header.setAlignItems(Alignment.CENTER);
        header.setJustifyContentMode(JustifyContentMode.BETWEEN);

        VerticalLayout titleBlock = new VerticalLayout();
        titleBlock.setPadding(false);
        titleBlock.setSpacing(false);
        H2 title = new H2("Agent Templates");
        title.getStyle().set("margin", "0");
        Paragraph sub = new Paragraph("Project: " + project.getName());
        sub.getStyle().set("color", "#73726c").set("margin", "0");
        titleBlock.add(title, sub);

        HorizontalLayout actions = new HorizontalLayout();
        Button backBtn = new Button("← Back to Project");
        backBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        backBtn.addClickListener(e -> getUI().ifPresent(ui ->
                ui.navigate("projects/" + projectId + "/settings")));

        Button createBtn = new Button("+ New Agent Template");
        createBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        createBtn.addClickListener(e -> openEditDialog(null));

        actions.add(backBtn, createBtn);
        header.add(titleBlock, actions);
        add(header);

        // Help text
        Paragraph help = new Paragraph(
                "Define reusable agent roles for your workflows. " +
                "Use {{requirement}}, {{tech_stack}}, {{previous_output.agentKey}} in task templates.");
        help.getStyle().set("color", "#73726c").set("font-size", "0.9em");
        add(help);

        // Grid
        grid = new Grid<>(AgentTemplate.class, false);
        grid.getStyle().set("background", BG_CARD).set("border", BORDER).set("border-radius", "8px");
        grid.addColumn(AgentTemplate::getName).setHeader("Name").setWidth("160px").setFlexGrow(0);
        grid.addColumn(AgentTemplate::getAgentKey).setHeader("Key").setWidth("130px").setFlexGrow(0);
        grid.addColumn(AgentTemplate::getRole).setHeader("Role").setFlexGrow(1);
        grid.addColumn(AgentTemplate::getGoal).setHeader("Goal").setFlexGrow(2);
        grid.addComponentColumn(template -> {
            HorizontalLayout btns = new HorizontalLayout();
            Button edit = new Button("Edit");
            edit.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            edit.addClickListener(e -> openEditDialog(template));
            Button delete = new Button("Delete");
            delete.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);
            delete.addClickListener(e -> {
                agentTemplateRepository.deleteById(template.getId());
                refreshGrid();
                Notification.show("Agent template deleted").addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            });
            btns.add(edit, delete);
            return btns;
        }).setHeader("Actions").setWidth("160px").setFlexGrow(0);

        add(grid);
        refreshGrid();
    }

    private void refreshGrid() {
        List<AgentTemplate> templates = agentTemplateRepository.findByProjectId(projectId);
        grid.setItems(templates);
    }

    private void openEditDialog(AgentTemplate existing) {
        Dialog dialog = new Dialog();
        dialog.setWidth("700px");
        dialog.setHeaderTitle(existing == null ? "New Agent Template" : "Edit Agent Template");

        FormLayout form = new FormLayout();
        form.setResponsiveSteps(new FormLayout.ResponsiveStep("0", 2));

        TextField nameField = new TextField("Display Name");
        nameField.setRequiredIndicatorVisible(true);
        TextField agentKeyField = new TextField("Agent Key");
        agentKeyField.setHelperText("Internal identifier, e.g. pm, dev, custom_reviewer");
        agentKeyField.setRequiredIndicatorVisible(true);
        TextField roleField = new TextField("Role");
        roleField.setRequiredIndicatorVisible(true);

        TextArea goalField = new TextArea("Goal");
        goalField.setMinHeight("80px");
        goalField.setRequiredIndicatorVisible(true);

        TextArea backstoryField = new TextArea("Backstory Template");
        backstoryField.setMinHeight("100px");
        backstoryField.setHelperText("Use {context} as placeholder for project context");

        TextArea taskDescField = new TextArea("Task Description Template");
        taskDescField.setMinHeight("120px");
        taskDescField.setHelperText("Use {{requirement}}, {{tech_stack}}, {{previous_output.agentKey}}");

        if (existing != null) {
            nameField.setValue(existing.getName());
            agentKeyField.setValue(existing.getAgentKey() != null ? existing.getAgentKey() : "");
            roleField.setValue(existing.getRole() != null ? existing.getRole() : "");
            goalField.setValue(existing.getGoal() != null ? existing.getGoal() : "");
            backstoryField.setValue(existing.getBackstoryTemplate() != null ? existing.getBackstoryTemplate() : "");
            taskDescField.setValue(existing.getTaskDescriptionTemplate() != null ? existing.getTaskDescriptionTemplate() : "");
        }

        form.add(nameField, agentKeyField);
        form.setColspan(roleField, 2);
        form.add(roleField);
        form.setColspan(goalField, 2);
        form.add(goalField);
        form.setColspan(backstoryField, 2);
        form.add(backstoryField);
        form.setColspan(taskDescField, 2);
        form.add(taskDescField);

        Button saveBtn = new Button("Save");
        saveBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveBtn.addClickListener(e -> {
            if (nameField.isEmpty() || roleField.isEmpty() || goalField.isEmpty()) {
                Notification.show("Name, Role and Goal are required").addThemeVariants(NotificationVariant.LUMO_ERROR);
                return;
            }
            AgentTemplate entity = existing != null ? existing : new AgentTemplate();
            entity.setProject(project);
            entity.setName(nameField.getValue().trim());
            entity.setAgentKey(agentKeyField.isEmpty()
                    ? nameField.getValue().toLowerCase().replace(" ", "_")
                    : agentKeyField.getValue().trim());
            entity.setRole(roleField.getValue().trim());
            entity.setGoal(goalField.getValue().trim());
            entity.setBackstoryTemplate(backstoryField.getValue());
            entity.setTaskDescriptionTemplate(taskDescField.getValue());
            agentTemplateRepository.save(entity);
            dialog.close();
            refreshGrid();
            Notification.show("Agent template saved").addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        });

        Button cancelBtn = new Button("Cancel");
        cancelBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        cancelBtn.addClickListener(e -> dialog.close());

        HorizontalLayout footer = new HorizontalLayout(cancelBtn, saveBtn);
        footer.setJustifyContentMode(JustifyContentMode.END);
        footer.setWidthFull();

        VerticalLayout content = new VerticalLayout(form, footer);
        content.setPadding(false);
        dialog.add(content);
        dialog.open();
    }
}
