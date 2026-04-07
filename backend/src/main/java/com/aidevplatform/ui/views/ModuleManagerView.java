package com.aidevplatform.ui.views;

import com.aidevplatform.domain.entity.Module;
import com.aidevplatform.domain.entity.Project;
import com.aidevplatform.service.FileStorageService;
import com.aidevplatform.service.ModuleService;
import com.aidevplatform.service.ProjectService;
import com.aidevplatform.ui.MainLayout;
import com.aidevplatform.ui.components.FileUploadComponent;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.button.Button;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.button.ButtonVariant;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.component.dialog.Dialog;
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
import com.vaadin.flow.router.*;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.router.RouteParameters;
import jakarta.annotation.security.PermitAll;
import com.vaadin.flow.theme.lumo.LumoUtility;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.UUID;

/**
 * Module manager view: create, edit, and trigger agent runs for modules.
 * Includes drag-and-drop file upload for requirement documents.
 */
@PermitAll
@Route(value = "projects/:projectId/modules", layout = MainLayout.class)
@PageTitle("Modules — AI Dev Platform")
@Slf4j
public class ModuleManagerView extends VerticalLayout implements BeforeEnterObserver {

    private final ProjectService projectService;
    private final ModuleService moduleService;
    private final FileStorageService fileStorageService;

    private UUID projectId;
    private final Grid<Module> grid = new Grid<>(Module.class, false);

    public ModuleManagerView(ProjectService projectService, ModuleService moduleService,
                              FileStorageService fileStorageService) {
        this.projectService = projectService;
        this.moduleService = moduleService;
        this.fileStorageService = fileStorageService;
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
            refreshGrid();
        } catch (Exception e) {
            log.error("Failed to initialize module manager: {}", idParam, e);
            event.forwardTo(ProjectListView.class);
        }
    }

    private void buildUI() {
        // Breadcrumb
        Project project = projectService.findById(projectId);
        HorizontalLayout breadcrumb = new HorizontalLayout(
                new RouterLink("Projects", ProjectListView.class),
                new Span("/"),
                new RouterLink(project.getName(), ProjectDetailView.class, new RouteParameters("projectId", projectId.toString())),
                new Span("/"),
                new Span("Modules")
        );
        breadcrumb.setAlignItems(Alignment.CENTER);
        breadcrumb.addClassNames(LumoUtility.Gap.XSMALL);
        add(breadcrumb);

        HorizontalLayout header = new HorizontalLayout();
        header.setAlignItems(Alignment.CENTER);
        header.setWidthFull();
        header.add(new H2("Modules"));
        header.addAndExpand(new Span());

        Button createBtn = new Button("New Module", e -> openCreateModuleDialog());
        createBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        header.add(createBtn);
        add(header);

        configureGrid();
        add(grid);
    }

    private void configureGrid() {
        grid.setSizeFull();
        grid.setHeight("500px");

        grid.addColumn(Module::getName).setHeader("Name").setFlexGrow(1);
        grid.addColumn(m -> m.getStatus().name()).setHeader("Status").setWidth("160px").setFlexGrow(0);
        grid.addColumn(m -> m.getRawRequirement() != null
                ? "Loaded (" + m.getRawRequirement().length() + " chars)"
                : "No requirement")
                .setHeader("Requirement").setWidth("200px").setFlexGrow(0);
        grid.addComponentColumn(module -> {
            HorizontalLayout actions = new HorizontalLayout();
            actions.setSpacing(true);

            Button uploadBtn = new Button("Upload Req", e -> openUploadDialog(module));
            uploadBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);

            Button runBtn = new Button("Run Agents", e -> triggerAgentRun(module));
            runBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_SUCCESS);
            runBtn.setEnabled(module.getRawRequirement() != null && !module.getRawRequirement().isBlank());

            Button monitorBtn = new Button("Monitor", e ->
                    getUI().ifPresent(ui -> ui.navigate(AgentMonitorView.class, new RouteParameters("moduleId", module.getId().toString()))));
            monitorBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);

            Button deleteBtn = new Button("Delete", e -> confirmDelete(module));
            deleteBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);

            actions.add(uploadBtn, runBtn, monitorBtn, deleteBtn);
            return actions;
        }).setHeader("Actions").setWidth("360px").setFlexGrow(0);
    }

    private void refreshGrid() {
        try {
            List<Module> modules = moduleService.findByProject(projectId);
            grid.setItems(modules);
        } catch (Exception e) {
            log.error("Failed to load modules", e);
            Notification.show("Failed to load modules", 3000, Notification.Position.TOP_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        }
    }

    private void openCreateModuleDialog() {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("New Module");
        dialog.setWidth("480px");

        TextField nameField = new TextField("Module Name");
        nameField.setWidthFull();
        nameField.setRequired(true);

        TextArea descField = new TextArea("Description");
        descField.setWidthFull();

        VerticalLayout content = new VerticalLayout(nameField, descField);
        content.setPadding(false);
        dialog.add(content);

        Button saveBtn = new Button("Create", e -> {
            if (nameField.isEmpty()) {
                nameField.setInvalid(true);
                return;
            }
            try {
                moduleService.create(projectId, nameField.getValue(), descField.getValue());
                dialog.close();
                refreshGrid();
                Notification.show("Module created", 3000, Notification.Position.TOP_END)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            } catch (Exception ex) {
                log.error("Failed to create module", ex);
                Notification.show("Failed: " + ex.getMessage(), 5000, Notification.Position.TOP_END)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        saveBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button cancelBtn = new Button("Cancel", e -> dialog.close());
        dialog.getFooter().add(cancelBtn, saveBtn);
        dialog.open();
    }

    private void openUploadDialog(Module module) {
        Dialog dialog = new Dialog();
        dialog.setHeaderTitle("Set Requirement — " + module.getName());
        dialog.setWidth("600px");

        VerticalLayout content = new VerticalLayout();
        content.setPadding(false);
        content.setSpacing(true);

        // Option 1: upload file → stored in local uploads/ folder (no MinIO needed)
        H3 fileTitle = new H3("Option 1 — Upload file");
        Paragraph fileHint = new Paragraph("File will be saved to the local uploads folder on the server.");
        fileHint.addClassNames(LumoUtility.FontSize.XSMALL, LumoUtility.TextColor.SECONDARY);

        FileUploadComponent uploadComponent = new FileUploadComponent();
        uploadComponent.addFileUploadedListener(event -> {
            try {
                String path = fileStorageService.storeRequirementFile(
                        module.getId(), event.getFileName(), event.getContent());
                moduleService.setRequirementFile(module.getId(), path, event.getContent());
                dialog.close();
                refreshGrid();
                Notification.show("File uploaded: " + event.getFileName(),
                        3000, Notification.Position.TOP_END)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            } catch (Exception e) {
                log.error("Failed to save requirement file", e);
                Notification.show("Failed to save: " + e.getMessage(),
                        5000, Notification.Position.TOP_END)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });

        // Divider
        Paragraph or = new Paragraph("— OR —");
        or.getStyle().set("text-align", "center").set("color", "var(--lumo-secondary-text-color)");

        // Option 2: paste text directly — no file upload required, saved straight to DB
        H3 pasteTitle = new H3("Option 2 — Paste requirement text");
        Paragraph pasteHint = new Paragraph("No file upload needed. Text is saved directly to the database.");
        pasteHint.addClassNames(LumoUtility.FontSize.XSMALL, LumoUtility.TextColor.SECONDARY);

        TextArea pasteArea = new TextArea();
        pasteArea.setWidthFull();
        pasteArea.setMinHeight("150px");
        pasteArea.setPlaceholder("Paste the raw requirement text here...");
        pasteArea.addValueChangeListener(e -> pasteArea.setInvalid(false));

        Button savePasteBtn = new Button("Save Text", e -> {
            String text = pasteArea.getValue();
            if (text == null || text.isBlank()) {
                pasteArea.setInvalid(true);
                pasteArea.setErrorMessage("Requirement text cannot be empty");
                return;
            }
            try {
                // Paste text goes straight to DB — no file storage call needed
                moduleService.setRequirementFile(module.getId(), null, text);
                dialog.close();
                refreshGrid();
                Notification.show("Requirement text saved", 3000, Notification.Position.TOP_END)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            } catch (Exception ex) {
                log.error("Failed to save pasted requirement", ex);
                Notification.show("Failed: " + ex.getMessage(), 5000, Notification.Position.TOP_END)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        savePasteBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        content.add(fileTitle, fileHint, uploadComponent, or, pasteTitle, pasteHint, pasteArea, savePasteBtn);
        dialog.add(content);

        Button cancelBtn = new Button("Cancel", e -> dialog.close());
        dialog.getFooter().add(cancelBtn);
        dialog.open();
    }

    private void triggerAgentRun(Module module) {
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("Start Agent Pipeline?");
        confirm.setText("This will start the AI agent pipeline for module '" + module.getName() +
                "'. All active agents will process the requirement in sequence.");
        confirm.setConfirmText("Start Pipeline");
        confirm.setConfirmButtonTheme("primary");
        confirm.setCancelable(true);
        confirm.addConfirmListener(e -> {
            try {
                moduleService.triggerAgentRun(module.getId());
                refreshGrid();
                Notification.show("Agent pipeline started for: " + module.getName(),
                        3000, Notification.Position.TOP_END)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
                getUI().ifPresent(ui -> ui.navigate(AgentMonitorView.class, new RouteParameters("moduleId", module.getId().toString())));
            } catch (Exception ex) {
                log.error("Failed to trigger agent run", ex);
                Notification.show("Failed to start: " + ex.getMessage(),
                        5000, Notification.Position.TOP_END)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        confirm.open();
    }

    private void confirmDelete(Module module) {
        ConfirmDialog confirm = new ConfirmDialog();
        confirm.setHeader("Delete Module?");
        confirm.setText("Delete '" + module.getName() + "'? This cannot be undone.");
        confirm.setConfirmText("Delete");
        confirm.setConfirmButtonTheme("error primary");
        confirm.setCancelable(true);
        confirm.addConfirmListener(e -> {
            try {
                moduleService.delete(module.getId());
                refreshGrid();
                Notification.show("Module deleted", 3000, Notification.Position.TOP_END)
                        .addThemeVariants(NotificationVariant.LUMO_SUCCESS);
            } catch (Exception ex) {
                log.error("Failed to delete module", ex);
                Notification.show("Failed: " + ex.getMessage(), 5000, Notification.Position.TOP_END)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });
        confirm.open();
    }
}
