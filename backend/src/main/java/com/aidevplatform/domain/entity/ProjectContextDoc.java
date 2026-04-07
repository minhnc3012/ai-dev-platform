package com.aidevplatform.domain.entity;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "project_context_docs")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ProjectContextDoc extends BaseEntity {

    private static final long serialVersionUID = 1L;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    // "erd", "code_sample", "api_convention", "style_guide", "other"
    @Column(nullable = false)
    private String docType;

    @Column(nullable = false)
    private String fileName;

    @Column(nullable = false)
    private String filePath;

    @Column(columnDefinition = "text")
    private String description;
}
