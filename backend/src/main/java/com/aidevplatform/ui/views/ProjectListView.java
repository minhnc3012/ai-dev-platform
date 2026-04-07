package com.aidevplatform.ui.views;

import com.aidevplatform.domain.entity.Project;
import com.aidevplatform.service.ProjectService;
import com.aidevplatform.ui.MainLayout;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.button.Button;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.button.ButtonVariant;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.dialog.Dialog;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.grid.Grid;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.html.H2;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.html.Paragraph;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.html.Span;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.notification.Notification;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.notification.NotificationVariant;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.textfield.TextArea;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.textfield.TextField;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.router.PageTitle;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.router.RouteParameters;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.router.RouterLink;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.theme.lumo.LumoUtility;
import lombok.extern.slf4j.Slf4j;

import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * View listing all projects for the current owner with create/edit capabilities.
 */
@PermitAll
@Route(value = "projects", layout = MainLayout.class)
@PageTitle("Projects — AI Dev Platform")
@Slf4j
public class ProjectListView extends VerticalLayout {

    private final ProjectService projectService;
    private final Grid<Project> grid = new Grid<>(Project.class, false);

    public ProjectListView(ProjectService projectService) {
        this.projectService = projectService;
        setPadding(true);
        setSpacing(true);
        setSizeFull();
        buildUI();
        refreshGrid();
    }

    private void buildUI() {
        HorizontalLayout header = new HorizontalLayout();
        header.setAlignItems(Alignment.CENTER);
        header.setWidthFull();

        H2 title = new H2("Projects");
        Button createBtn = new Button("New Project", e -> openCreateDialog());
        createBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        header.add(title);
        header.addAndExpand(new Span());
        header.add(createBtn);
        add(header);

        // Configure grid
        grid.setSizeFull();
        grid.addColumn(project -> {
                    RouterLink link = new RouterLink(project.getName(), ProjectDetailView.class, new RouteParameters("projectId", project.getId().toString()));
                    return link;
                })
                .setHeader("Name")
                .setKey("name")
                .setRenderer(new com.vaadin.flow.data.renderer.ComponentRenderer<>(project -> {
                    RouterLink link = new RouterLink(project.getName(), ProjectDetailView.class, new RouteParameters("projectId", project.getId().toString()));
                    return link;
                }));
        grid.addColumn(p -> p.getStatus().name()).setHeader("Status").setWidth("120px").setFlexGrow(0);
        grid.addColumn(p -> p.getDescription() != null ? p.getDescription() : "—")
                .setHeader("Description");
        grid.addColumn(p -> p.getCreatedAt() != null
                        ? p.getCreatedAt().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                        : "—")
                .setHeader("Created").setWidth("180px").setFlexGrow(0);
        grid.addComponentColumn(project -> {
            Button settings = new Button("Settings", e ->
                    getUI().ifPresent(ui -> ui.navigate(ProjectSettingsView.class, new RouteParameters("projectId", project.getId().toString()))));
            settings.addThemeVariants(ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_SMALL);
            return settings;
        }).setHeader("Actions").setWidth("120px").setFlexGrow(0);

        add(grid);
    }

    private void refreshGrid() {
        try {
            List<Project> projects = projectService.findAllByOwner(
                    UUID.fromString("00000000-0000-0000-0000-000000000001"));
            grid.setItems(projects);
        } catch (Exception e) {
            log.error("Failed to load projects", e);
            Notification.show("Failed to load projects", 3000, Notification.Position.TOP_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private void openCreateDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("New Project");
        dialog.setWidth("480px");

        TextField nameField = new TextField("Project Name");
        nameField.setWidthFull();
        nameField.setRequired(true);

        TextArea descField = new TextArea("Description");
        descField.setWidthFull();
        descField.setMinHeight("80px");

        TextField workspaceField = new TextField("Workspace Path");
        workspaceField.setWidthFull();
        workspaceField.setPlaceholder("/home/user/projects/my-project");
        workspaceField.setHelperText("Absolute path where agents will write generated files");

        VerticalLayout content = new VerticalLayout(nameField, descField, workspaceField);
        content.setPadding(false);
        dialog.add(content);

        Button saveBtn = new Button("Create", e -> {
            if (nameField.isEmpty()) {
                nameField.setInvalid(true);
                return;
            }
            try {
                projectService.create(nameField.getValue(), descField.getValue(),
                        workspaceField.getValue(),
                        UUID.fromString("00000000-0000-0000-0000-000000000001"));
                dialog.close();
                refreshGrid();
                Notification.show("Project created", 3000, Notification.Position.TOP_END)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            } catch (Exception ex) {
                log.error("Failed to create project", ex);
                Notification.show("Failed to create project: " + ex.getMessage(),
                        5000, Notification.Position.TOP_END)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        saveBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button cancelBtn = new Button("Cancel", e -> dialog.close());
        cancelBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        dialog.getFooter().add(cancelBtn, saveBtn);
        dialog.open();
    }
}
