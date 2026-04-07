package com.aidevplatform.ui.views;

import com.aidevplatform.domain.entity.Module;
import com.aidevplatform.domain.entity.Project;
import com.aidevplatform.domain.enums.ProjectStatus;
import com.aidevplatform.service.ModuleService;
import com.aidevplatform.service.ProjectService;
import com.aidevplatform.ui.MainLayout;
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
import com.vaadin.flow.router.PageTitle;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.router.RouteParameters;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.router.RouterLink;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.theme.lumo.LumoUtility;
import java.util.List;
import java.util.UUID;

/**
 * Dashboard view showing an overview of projects and recent activity.
 */
@PermitAll
@Route(value = "", layout = MainLayout.class)
@PageTitle("Dashboard — AI Dev Platform")
public class DashboardView extends VerticalLayout {

    private final ProjectService projectService;

    public DashboardView(ProjectService projectService) {
        this.projectService = projectService;
        setPadding(true);
        setSpacing(true);
        buildUI();
    }

    private void buildUI() {
        add(new H2("Dashboard"));

        // Summary cards row
        HorizontalLayout cardsRow = new HorizontalLayout();
        cardsRow.setWidthFull();
        cardsRow.setSpacing(true);

        // Load projects for the current user
        // In a full implementation this would use the authenticated user's ID
        List<Project> projects = loadProjects();

        long activeCount = projects.stream()
                .filter(p -> p.getStatus() == ProjectStatus.ACTIVE)
                .count();

        cardsRow.add(
                createStatCard("Active Projects", String.valueOf(activeCount), "blue"),
                createStatCard("Total Projects", String.valueOf(projects.size()), "gray")
        );
        add(cardsRow);

        // Recent projects
        add(new H3("Recent Projects"));
        if (projects.isEmpty()) {
            Paragraph empty = new Paragraph("No projects yet. Create your first project to get started.");
            empty.addClassNames(LumoUtility.TextColor.SECONDARY);
            add(empty);
        } else {
            projects.stream().limit(5).forEach(project -> {
                RouterLink link = new RouterLink(project.getName(), ProjectDetailView.class, new RouteParameters("projectId", project.getId().toString()));
                Paragraph desc = new Paragraph(project.getDescription() != null ? project.getDescription() : "No description");
                desc.addClassNames(LumoUtility.FontSize.SMALL, LumoUtility.TextColor.SECONDARY);
                VerticalLayout card = new VerticalLayout(link, desc);
                card.setPadding(true);
                card.setSpacing(false);
                card.getStyle()
                        .set("border", "1px solid var(--lumo-contrast-10pct)")
                        .set("border-radius", "var(--lumo-border-radius-m)");
                add(card);
            });
        }

        // Link to projects
        RouterLink viewAll = new RouterLink("View all projects", ProjectListView.class);
        viewAll.addClassNames(LumoUtility.Margin.Top.MEDIUM);
        add(viewAll);
    }

    private VerticalLayout createStatCard(String label, String value, String color) {
        Span valueSpan = new Span(value);
        valueSpan.addClassNames(LumoUtility.FontSize.XXXLARGE, LumoUtility.FontWeight.BOLD);

        Span labelSpan = new Span(label);
        labelSpan.addClassNames(LumoUtility.FontSize.SMALL, LumoUtility.TextColor.SECONDARY);

        VerticalLayout card = new VerticalLayout(valueSpan, labelSpan);
        card.setPadding(true);
        card.setAlignItems(Alignment.START);
        card.setWidth("200px");
        card.getStyle()
                .set("border", "1px solid var(--lumo-contrast-10pct)")
                .set("border-radius", "var(--lumo-border-radius-m)");
        return card;
    }

    private List<Project> loadProjects() {
        try {
            // In production, use the authenticated user's ID
            return projectService.findAllByOwner(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        } catch (Exception e) {
            return List.of();
        }
    }
}
