package com.aidevplatform.ui.views;

import com.aidevplatform.domain.entity.Module;
import com.aidevplatform.service.ModuleService;
import com.aidevplatform.ui.MainLayout;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.html.H2;
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

import java.util.UUID;

/**
 * Detail view for a single module showing its status and agent run history.
 */
@PermitAll
@Route(value = "modules/:moduleId", layout = MainLayout.class)
@PageTitle("Module — AI Dev Platform")
@Slf4j
public class ModuleDetailView extends VerticalLayout implements BeforeEnterObserver {

    private final ModuleService moduleService;
    private UUID moduleId;

    public ModuleDetailView(ModuleService moduleService) {
        this.moduleService = moduleService;
        setPadding(true);
        setSpacing(true);
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
            Module module = moduleService.findById(moduleId);
            buildUI(module);
        } catch (Exception e) {
            log.error("Failed to load module: {}", idParam, e);
            event.forwardTo(ProjectListView.class);
        }
    }

    private void buildUI(Module module) {
        // Breadcrumb
        HorizontalLayout breadcrumb = new HorizontalLayout(
                new RouterLink("Projects", ProjectListView.class),
                new Span("/"),
                new RouterLink(module.getProject().getName(),
                        ProjectDetailView.class, new RouteParameters("projectId", module.getProject().getId().toString())),
                new Span("/"),
                new Span(module.getName())
        );
        breadcrumb.setAlignItems(Alignment.CENTER);
        breadcrumb.addClassNames(LumoUtility.Gap.XSMALL);
        add(breadcrumb);

        add(new H2(module.getName()));

        Paragraph status = new Paragraph("Status: " + module.getStatus().name());
        status.addClassNames(LumoUtility.TextColor.SECONDARY);
        add(status);

        if (module.getDescription() != null) {
            add(new Paragraph(module.getDescription()));
        }

        // Navigation shortcuts
        RouterLink monitorLink = new RouterLink("Open Agent Monitor",
                AgentMonitorView.class, new RouteParameters("moduleId", moduleId.toString()));
        RouterLink reportLink = new RouterLink("View Reports",
                ReportView.class, new RouteParameters("moduleId", moduleId.toString()));

        HorizontalLayout links = new HorizontalLayout(monitorLink, reportLink);
        links.setSpacing(true);
        add(links);
    }
}
