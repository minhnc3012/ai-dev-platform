package com.aidevplatform.ui.components;

import com.vaadin.flow.component.ComponentEvent;
import com.vaadin.flow.component.ComponentEventListener;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.notification.NotificationVariant;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.component.upload.receivers.MemoryBuffer;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.theme.lumo.LumoUtility;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Reusable file upload component for requirement files.
 * Accepts text files up to 10 MB and emits an event when a file is successfully read.
 */
@Slf4j
public class FileUploadComponent extends Div {

    private static final int MAX_FILE_SIZE = 10 * 1024 * 1024; // 10 MB
    private static final String[] ACCEPTED_TYPES = {
            "text/plain", "text/markdown", ".md", ".txt", ".pdf"
    };

    private final MemoryBuffer buffer = new MemoryBuffer();
    private final Upload upload = new Upload(buffer);

    public FileUploadComponent() {
        addClassNames(LumoUtility.Width.FULL);
        configureUpload();
        buildUI();
    }

    private void configureUpload() {
        upload.setMaxFileSize(MAX_FILE_SIZE);
        upload.setAcceptedFileTypes(ACCEPTED_TYPES);
        upload.setMaxFiles(1);

        Span dropLabel = new Span("Drop requirement file here (TXT, MD) or click to browse");
        dropLabel.addClassNames(LumoUtility.TextColor.SECONDARY);
        upload.setDropLabel(dropLabel);

        upload.addSucceededListener(event -> {
            try {
                InputStream inputStream = buffer.getInputStream();
                String content = new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
                fireEvent(new FileUploadedEvent(this, false, event.getFileName(), content));
            } catch (Exception e) {
                log.error("Failed to read uploaded file: {}", event.getFileName(), e);
                Notification.show("Failed to read file: " + e.getMessage(),
                        5000, Notification.Position.TOP_END)
                        .addThemeVariants(NotificationVariant.LUMO_ERROR);
            }
        });

        upload.addFailedListener(event -> {
            log.warn("File upload failed: {}", event.getReason().getMessage());
            Notification.show("Upload failed: " + event.getReason().getMessage(),
                    5000, Notification.Position.TOP_END)
                    .addThemeVariants(NotificationVariant.LUMO_ERROR);
        });
    }

    private void buildUI() {
        Paragraph hint = new Paragraph("Supported formats: TXT, Markdown. Max size: 10 MB.");
        hint.addClassNames(LumoUtility.FontSize.XSMALL, LumoUtility.TextColor.SECONDARY);
        add(upload, hint);
    }

    public Registration addFileUploadedListener(ComponentEventListener<FileUploadedEvent> listener) {
        return addListener(FileUploadedEvent.class, listener);
    }

    /**
     * Event fired when a file is successfully uploaded and its content read.
     */
    @Getter
    public static class FileUploadedEvent extends ComponentEvent<FileUploadComponent> {
        private final String fileName;
        private final String content;

        public FileUploadedEvent(FileUploadComponent source, boolean fromClient,
                                  String fileName, String content) {
            super(source, fromClient);
            this.fileName = fileName;
            this.content = content;
        }
    }
}
