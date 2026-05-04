package com.aidevplatform.ui.views;

import com.aidevplatform.domain.entity.AiConfig;
import com.aidevplatform.domain.entity.Project;
import com.aidevplatform.service.ProjectService;
import com.aidevplatform.ui.MainLayout;
import com.aidevplatform.ui.components.AiConfigForm;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.*;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.theme.lumo.LumoUtility;
import jakarta.annotation.security.PermitAll;
import lombok.extern.slf4j.Slf4j;

import java.util.UUID;

/**
 * Settings view for a project: general details + AI configuration.
 */
@PermitAll
@Route(value = "projects/:projectId/settings", layout = MainLayout.class)
@PageTitle("Project Settings — AI Dev Platform")
@Slf4j
public class ProjectSettingsView extends VerticalLayout implements BeforeEnterObserver {

    private final ProjectService projectService;

    private UUID projectId;
    private Project project;

    private final TextField projectName = new TextField("Project Name");
    private final TextField gitRepoUrl = new TextField("Git Repository URL");
    private final TextField workspacePath = new TextField("Workspace Path");
    private final com.vaadin.flow.component.textfield.TextArea projectDesc =
            new com.vaadin.flow.component.textfield.TextArea("Description");
    private final AiConfigForm aiConfigForm = new AiConfigForm();

    public ProjectSettingsView(ProjectService projectService) {
        this.projectService = projectService;
        setPadding(true);
        setSpacing(true);
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
            project = projectService.findByIdWithAiConfig(projectId);
            buildUI();
            loadData();
        } catch (Exception e) {
            log.error("Failed to load project settings: {}", idParam, e);
            event.forwardTo(ProjectListView.class);
        }
    }

    private void buildUI() {
        removeAll();

        // Breadcrumb
        HorizontalLayout breadcrumb = new HorizontalLayout(
                new RouterLink("Projects", ProjectListView.class),
                new Span("/"),
                new RouterLink(project.getName(), ProjectDetailView.class, new RouteParameters("projectId", projectId.toString())),
                new Span("/"),
                new Span("Settings")
        );
        breadcrumb.setAlignItems(Alignment.CENTER);
        breadcrumb.addClassNames(LumoUtility.Gap.XSMALL);
        add(breadcrumb);

        add(new H2("Project Settings"));

        // General settings section
        add(new H3("General"));
        projectName.setWidthFull();
        projectName.setRequired(true);
        projectDesc.setWidthFull();
        projectDesc.setMinHeight("80px");
        gitRepoUrl.setWidthFull();
        gitRepoUrl.setPlaceholder("https://github.com/org/repo");
        workspacePath.setWidthFull();
        workspacePath.setPlaceholder("/home/user/projects/my-project");
        workspacePath.setHelperText("Absolute path where agents will write generated files (code, docs, tests...)");

        Button saveGeneralBtn = new Button("Save General Settings", e -> saveGeneralSettings());
        saveGeneralBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        VerticalLayout generalSection = new VerticalLayout(projectName, projectDesc, gitRepoUrl, workspacePath, saveGeneralBtn);
        generalSection.setPadding(false);
        generalSection.setSpacing(true);
        add(generalSection);

        // AI Configuration section
        add(new H3("AI Configuration"));
        add(buildProviderGuide());
        add(aiConfigForm);

        Button saveAiBtn = new Button("Save AI Configuration", e -> saveAiConfig());
        saveAiBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        add(saveAiBtn);
    }

    private VerticalLayout buildProviderGuide() {
        VerticalLayout guide = new VerticalLayout();
        guide.setPadding(true);
        guide.setSpacing(false);
        guide.getStyle()
                .set("background", "var(--lumo-contrast-5pct)")
                .set("border-radius", "var(--lumo-border-radius-m)");

        Span title = new Span("Provider Quick Reference");
        title.addClassNames(LumoUtility.FontWeight.SEMIBOLD, LumoUtility.FontSize.SMALL);

        String[][] providers = {
                {"Claude CLI + local LLM", "CLI", "—", "claude --settings D:/Tools/local-llm.json --model qwen3.5:35b"},
                {"LM Studio (local)", "API", "http://localhost:1234/v1", "qwen3.5-35b"},
                {"Ollama server (local)", "API", "http://localhost:11434/v1", "qwen3.5:35b"},
                {"Ollama CLI (local)", "CLI", "—", "ollama run qwen3.5:35b"},
                {"Alibaba Cloud", "API", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-max"},
                {"OpenAI", "SDK", "—", "gpt-4o"},
                {"Anthropic", "SDK", "—", "claude-sonnet-4-6"},
        };

        StringBuilder sb = new StringBuilder();
        for (String[] row : providers) {
            sb.append(String.format("%-22s | %-5s | %-55s | %s%n",
                    row[0], row[1], row[2], row[3]));
        }
        com.vaadin.flow.component.html.Pre pre = new com.vaadin.flow.component.html.Pre(sb.toString());
        pre.getStyle().set("font-size", "11px").set("overflow-x", "auto");
        guide.add(title, pre);
        return guide;
    }

    private void loadData() {
        projectName.setValue(project.getName());
        projectDesc.setValue(project.getDescription() != null ? project.getDescription() : "");
        gitRepoUrl.setValue(project.getGitRepoUrl() != null ? project.getGitRepoUrl() : "");
        workspacePath.setValue(project.getWorkspacePath() != null ? project.getWorkspacePath() : "");
        aiConfigForm.loadFrom(project.getAiConfig());
    }

    private void saveGeneralSettings() {
        if (projectName.isEmpty()) {
            projectName.setInvalid(true);
            return;
        }
        try {
            projectService.update(projectId, projectName.getValue(),
                    projectDesc.getValue(), gitRepoUrl.getValue(), workspacePath.getValue());
            Notification.show("General settings saved", 3000, Notification.Position.TOP_END)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        } catch (Exception e) {
            log.error("Failed to save general settings", e);
            Notification.show("Failed to save: " + e.getMessage(), 5000, Notification.Position.TOP_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private void saveAiConfig() {
        try {
            AiConfig config = project.getAiConfig() != null ? project.getAiConfig() : new AiConfig();
            aiConfigForm.writeTo(config);
            projectService.updateAiConfig(projectId, config);
            Notification.show("AI configuration saved", 3000, Notification.Position.TOP_END)
                    .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
        } catch (Exception e) {
            log.error("Failed to save AI config", e);
            Notification.show("Failed to save AI configuration: " + e.getMessage(),
                    5000, Notification.Position.TOP_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }
}
