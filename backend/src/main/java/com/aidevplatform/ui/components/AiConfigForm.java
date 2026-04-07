package com.aidevplatform.ui.components;

import com.aidevplatform.domain.entity.AiConfig;
import com.aidevplatform.domain.enums.LlmInvocationMode;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.checkbox.CheckboxGroup;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.Hr;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.select.Select;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.NumberField;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.theme.lumo.LumoUtility;

import java.math.BigDecimal;
import java.util.*;

/**
 * Form component for editing per-project AI configuration.
 * Renders conditional fields based on the selected LlmInvocationMode.
 * Can be embedded in ProjectSettingsView or shown as a dialog.
 */
public class AiConfigForm extends FormLayout {

    // Flag to prevent event listener from triggering during programmatic config load
    private final java.util.concurrent.atomic.AtomicBoolean loadingConfig =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    private final Select<String> llmProvider = new Select<>();
    private final RadioButtonGroup<LlmInvocationMode> invocationMode = new RadioButtonGroup<>();
    private final TextField llmBaseUrl = new TextField("Base URL");
    private final TextField llmModelName = new TextField("Model name");
    private final PasswordField llmApiKey = new PasswordField("API key");
    private final TextField llmCliCommand = new TextField("CLI command");
    private final NumberField temperature = new NumberField("Temperature");
    private final IntegerField maxTokensPerTask = new IntegerField("Max tokens per task");
    private final Select<String> outputLanguage = new Select<>();
    private final Checkbox approvalRequired = new Checkbox("Require owner approval between agents");
    private final CheckboxGroup<String> activeAgents = new CheckboxGroup<>();
    private final TextField techStackField = new TextField("Tech stack (comma-separated)");
    private final TextArea codingStyleGuide = new TextArea("Coding style guide");

    public AiConfigForm() {
        configureFields();
        bindModeVisibility();
        setResponsiveSteps(new ResponsiveStep("0", 2));
    }

    private void configureFields() {
        llmProvider.setLabel("LLM provider");
        llmProvider.setItems(
                "openai_compatible", "openai", "anthropic",
                "ollama_cli", "llama_cli", "custom"
        );
        llmProvider.setValue("openai_compatible");
        llmProvider.setWidthFull();

        invocationMode.setLabel("Invocation mode");
        invocationMode.setItems(LlmInvocationMode.values());
        invocationMode.setValue(LlmInvocationMode.API);
        invocationMode.setItemLabelGenerator(mode -> switch (mode) {
            case API -> "API — OpenAI-compatible HTTP endpoint (recommended for local Ollama server)";
            case CLI -> "CLI — Local subprocess (claude -p, ollama run, llama-cli...)";
            case SDK -> "SDK — Provider-specific Python SDK";
        });

        llmBaseUrl.setPlaceholder("http://14.224.xxx.xxx:xxx");
        llmBaseUrl.setHelperText("Base URL — do not include /v1 or /chat/completions");
        llmBaseUrl.setWidthFull();

        llmModelName.setPlaceholder("qwen3.5:35b");
        llmModelName.setWidthFull();

        llmApiKey.setPlaceholder("Leave empty for local models (Ollama does not require API key)");
        llmApiKey.setWidthFull();

        llmCliCommand.setPlaceholder("ollama run qwen3.5:35b");
        llmCliCommand.setHelperText("Full CLI command — model name must be included");
        llmCliCommand.setWidthFull();

        temperature.setMin(0);
        temperature.setMax(1);
        temperature.setStep(0.05);
        temperature.setValue(0.5);
        temperature.setWidthFull();

        maxTokensPerTask.setMin(256);
        maxTokensPerTask.setMax(32768);
        maxTokensPerTask.setValue(4096);
        maxTokensPerTask.setWidthFull();

        outputLanguage.setLabel("Report language");
        outputLanguage.setItems("en", "vi");
        outputLanguage.setItemLabelGenerator(lang -> "en".equals(lang) ? "English" : "Vietnamese");
        outputLanguage.setValue("en");
        outputLanguage.setWidthFull();

        activeAgents.setLabel("Active agents");
        activeAgents.setItems("pm", "architect", "dev", "qa", "docs");
        activeAgents.setValue(Set.of("pm", "architect", "dev", "qa", "docs"));
        activeAgents.setItemLabelGenerator(agent -> switch (agent) {
            case "pm" -> "PM — Requirement analysis";
            case "architect" -> "Architect — System design";
            case "dev" -> "Dev — Code generation";
            case "qa" -> "QA — Testing and review";
            case "docs" -> "Docs — Documentation";
            default -> agent;
        });

        techStackField.setPlaceholder("Java, Spring Boot, PostgreSQL, React");
        techStackField.setHelperText("Technologies used in this project, separated by commas");
        techStackField.setWidthFull();

        codingStyleGuide.setPlaceholder("Describe your coding conventions, naming rules, etc.");
        codingStyleGuide.setMinHeight("80px");
        codingStyleGuide.setWidthFull();

        // Layout: 2-column grid
        setColspan(invocationMode, 2);
        setColspan(approvalRequired, 2);
        setColspan(activeAgents, 2);
        setColspan(techStackField, 2);
        setColspan(codingStyleGuide, 2);

        add(llmProvider, invocationMode,
                llmBaseUrl, llmModelName,
                llmApiKey, llmCliCommand,
                temperature, maxTokensPerTask,
                outputLanguage, approvalRequired,
                activeAgents, techStackField, codingStyleGuide);
    }

    /** Show/hide fields whenever the invocation mode changes. */
    private void bindModeVisibility() {
        invocationMode.addValueChangeListener(e -> updateFieldVisibility(e.getValue()));
        updateFieldVisibility(invocationMode.getValue()); // initial state
    }

    private void updateFieldVisibility(LlmInvocationMode mode) {
        llmBaseUrl.setVisible(mode == LlmInvocationMode.API);
        llmModelName.setVisible(mode == LlmInvocationMode.API || mode == LlmInvocationMode.SDK);
        llmApiKey.setVisible(mode == LlmInvocationMode.API || mode == LlmInvocationMode.SDK);
        llmCliCommand.setVisible(mode == LlmInvocationMode.CLI);

        if (mode == LlmInvocationMode.SDK) {
            llmApiKey.setRequired(true);
            llmApiKey.setHelperText("Required for SDK mode");
        } else {
            llmApiKey.setRequired(false);
            llmApiKey.setHelperText("Leave empty for local models");
        }
    }

    /** Populate the form with an existing AiConfig. */
    public void loadFrom(AiConfig config) {
        if (config == null) return;

        // Use a flag to prevent event listener from triggering during load
        loadingConfig.set(true);
        try {
            String provider = config.getLlmProvider() != null ? config.getLlmProvider() : "openai_compatible";
            LlmInvocationMode mode = config.getInvocationMode() != null ? config.getInvocationMode() : LlmInvocationMode.API;

            llmProvider.setValue(provider);
            invocationMode.setValue(mode);
            llmBaseUrl.setValue(Optional.ofNullable(config.getLlmBaseUrl()).orElse(""));
            llmModelName.setValue(Optional.ofNullable(config.getLlmModelName()).orElse(""));
            llmApiKey.setValue(Optional.ofNullable(config.getLlmApiKey()).orElse(""));
            llmCliCommand.setValue(Optional.ofNullable(config.getLlmCliCommand()).orElse("claude -p"));
            temperature.setValue(config.getTemperature() != null ? config.getTemperature().doubleValue() : 0.5);
            maxTokensPerTask.setValue(config.getMaxTokensPerTask() != null ? config.getMaxTokensPerTask() : 4096);
            outputLanguage.setValue(Optional.ofNullable(config.getOutputLanguage()).orElse("en"));
            approvalRequired.setValue(Boolean.TRUE.equals(config.getApprovalRequired()));
            activeAgents.setValue(new HashSet<>(
                    Optional.ofNullable(config.getActiveAgents()).orElse(List.of())));

            List<String> stack = Optional.ofNullable(config.getTechStack()).orElse(List.of());
            techStackField.setValue(String.join(", ", stack));
            codingStyleGuide.setValue(Optional.ofNullable(config.getCodingStyleGuide()).orElse(""));

            // Sync visibility after loading
            updateFieldVisibility(mode);
        } finally {
            loadingConfig.set(false);
        }
    }

    /** Write form values back into an AiConfig entity. */
    public void writeTo(AiConfig config) {
        config.setLlmProvider(llmProvider.getValue());
        config.setInvocationMode(invocationMode.getValue());
        config.setLlmBaseUrl(llmBaseUrl.getValue().isBlank() ? null : llmBaseUrl.getValue());
        config.setLlmModelName(llmModelName.getValue().isBlank() ? null : llmModelName.getValue());
        config.setLlmApiKey(llmApiKey.getValue().isBlank() ? null : llmApiKey.getValue());
        config.setLlmCliCommand(llmCliCommand.getValue().isBlank() ? null : llmCliCommand.getValue());
        config.setTemperature(temperature.getValue() != null
                ? BigDecimal.valueOf(temperature.getValue()) : new BigDecimal("0.50"));
        config.setMaxTokensPerTask(maxTokensPerTask.getValue() != null ? maxTokensPerTask.getValue() : 4096);
        config.setOutputLanguage(outputLanguage.getValue());
        config.setApprovalRequired(approvalRequired.getValue());
        config.setActiveAgents(new ArrayList<>(activeAgents.getValue()));

        // Parse tech stack from comma-separated input
        String stackRaw = techStackField.getValue();
        List<String> stack = stackRaw.isBlank() ? List.of()
                : Arrays.stream(stackRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        config.setTechStack(new ArrayList<>(stack));
        config.setCodingStyleGuide(codingStyleGuide.getValue().isBlank() ? null : codingStyleGuide.getValue());
    }
}
