package com.aidevplatform.ui.views;

import com.aidevplatform.domain.entity.Module;
import com.aidevplatform.domain.entity.Project;
import com.aidevplatform.service.ModuleService;
import com.aidevplatform.service.ProjectService;
import com.aidevplatform.ui.MainLayout;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.button.Button;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.button.ButtonVariant;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.grid.Grid;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.html.H2;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.html.H3;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.html.Paragraph;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.html.Span;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.router.*;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.router.RouteParameters;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.theme.lumo.LumoUtility;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;

/**
 * Detail view for a single project showing its modules and actions.
 */
@PermitAll
@Route(value = "projects/:projectId", layout = MainLayout.class)
@PageTitle("Project — AI Dev Platform")
@Slf4j
public class ProjectDetailView extends VerticalLayout implements BeforeEnterObserver {

    private final ProjectService projectService;
    private final ModuleService moduleService;

    private UUID projectId;
    private final H2 titleLabel = new H2();
    private final Paragraph descLabel = new Paragraph();
    private final Grid<Module> moduleGrid = new Grid<>(Module.class, false);

    public ProjectDetailView(ProjectService projectService, ModuleService moduleService) {
        this.projectService = projectService;
        this.moduleService = moduleService;
        setPadding(true);
        setSpacing(true);
        setSizeFull();
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        String idParam = event.getRouteParameters().get("projectId").orElse(null);
        if (idParam == null) {
            event.forwardTo(ProjectListView.class);
            return;
        }
        try {
            projectId = UUID.fromString(idParam);
            buildUI();
            loadData();
        } catch (Exception e) {
            log.error("Failed to load project: {}", idParam, e);
            event.forwardTo(ProjectListView.class);
        }
    }

    private void buildUI() {
        removeAll();

        // Breadcrumb
        HorizontalLayout breadcrumb = new HorizontalLayout(
                new RouterLink("Projects", ProjectListView.class),
                new Span("/"),
                titleLabel
        );
        breadcrumb.setAlignItems(Alignment.CENTER);
        breadcrumb.setSpacing(false);
        breadcrumb.addClassNames(LumoUtility.Gap.SMALL);
        add(breadcrumb);

        // Description
        descLabel.addClassNames(LumoUtility.TextColor.SECONDARY);
        add(descLabel);

        // Action buttons
        Button settingsBtn = new Button("Settings", e ->
                getUI().ifPresent(ui -> ui.navigate(ProjectSettingsView.class, new RouteParameters("projectId", projectId.toString()))));
        settingsBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        Button modulesBtn = new Button("Manage Modules", e ->
                getUI().ifPresent(ui -> ui.navigate(ModuleManagerView.class, new RouteParameters("projectId", projectId.toString()))));
        modulesBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        HorizontalLayout actions = new HorizontalLayout(settingsBtn, modulesBtn);
        add(actions);

        // Modules section
        add(new H3("Modules"));
        configureModuleGrid();
        add(moduleGrid);
    }

    private void configureModuleGrid() {
        moduleGrid.setWidthFull();
        moduleGrid.setHeight("400px");

        moduleGrid.addColumn(Module::getName).setHeader("Name");
        moduleGrid.addColumn(m -> m.getStatus().name()).setHeader("Status").setWidth("160px").setFlexGrow(0);
        moduleGrid.addColumn(m -> m.getCurrentAgent() != null ? m.getCurrentAgent() : "—")
                .setHeader("Current Agent").setWidth("140px").setFlexGrow(0);
        moduleGrid.addComponentColumn(module -> {
            HorizontalLayout actions = new HorizontalLayout();
            Button monitorBtn = new Button("Monitor", e ->
                    getUI().ifPresent(ui -> ui.navigate(AgentMonitorView.class, new RouteParameters("moduleId", module.getId().toString()))));
            monitorBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);

            Button reportBtn = new Button("Report", e ->
                    getUI().ifPresent(ui -> ui.navigate(ReportView.class, new RouteParameters("moduleId", module.getId().toString()))));
            reportBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);

            actions.add(monitorBtn, reportBtn);
            return actions;
        }).setHeader("Actions").setWidth("200px").setFlexGrow(0);
    }

    private void loadData() {
        try {
            Project project = projectService.findById(projectId);
            titleLabel.setText(project.getName());
            descLabel.setText(project.getDescription() != null ? project.getDescription() : "");

            List<Module> modules = moduleService.findByProject(projectId);
            moduleGrid.setItems(modules);
        } catch (Exception e) {
            log.error("Failed to load project data: {}", projectId, e);
        }
    }
}
