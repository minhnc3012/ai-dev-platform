# AI Dev Platform — Claude Code Specification

> **Purpose**: This document is used by Claude Code to implement the full Vaadin application for AI-powered software project management.
> **Primary AI model**: Qwen3.5:35b running locally via LM Studio (OpenAI-compatible API) — switchable at runtime
> **Stack**: Java 21 + Spring Boot 3.x + Vaadin 24 + PostgreSQL + Redis + Python (CrewAI)

---

## 0. Ground Rules for Claude Code

These rules apply to **every file Claude Code creates or modifies** in this project. Read them before writing a single line.

### 0.1 Language policy

| Context | Language |
|---|---|
| This spec document | Vietnamese or English (owner's choice) |
| Conversations with owner | Vietnamese or English (owner's choice) |
| All source code (Java, Python, SQL, YAML, shell) | **English only** |
| Class names, method names, variable names | **English only** |
| Code comments and Javadoc | **English only** |
| Log messages (what appears in log files) | **English only** |
| UI label strings in Java/Vaadin code | **English only** |
| Git commit messages | **English only** |
| Exception messages | **English only** |

**Rationale**: The application must be maintainable by any developer regardless of language background. Mixing Vietnamese in source code creates confusion in diffs, search, and onboarding.

**Correct example:**
```java
// Retrieve all active projects for the given owner
public List<Project> findActiveProjectsByOwner(UUID ownerId) { ... }
```

**Wrong example:**
```java
// Lấy danh sách project — WRONG: Vietnamese in source code
public List<Project> layDanhSachProject(UUID ownerId) { ... }
```

### 0.2 LLM provider flexibility

The application must **never hardcode a specific model or provider**. All LLM configuration is stored in `ai_configs` and resolved at runtime. The system supports three invocation modes:

- **`API`** — Call any OpenAI-compatible HTTP endpoint (local LM Studio, Ollama, Alibaba Cloud, OpenAI, Anthropic, etc.)
- **`CLI`** — Invoke a local CLI tool (e.g. `ollama run`, `llama-cli`) via subprocess
- **`SDK`** — Use provider-specific Python SDK (e.g. `anthropic` package, `openai` package)

The active provider and mode can be changed per-project at any time from the UI without restarting the application.

---

## 1. Application Overview

The application allows an Owner to create software projects, upload raw customer requirements into modules/features, and then have an AI agent team (PM Agent → Architect → Dev → QA → Docs) automatically process those requirements and produce artifacts (user stories, design docs, code, tests, documentation). The Owner monitors each agent's status in real-time and receives a structured report when each task completes.

### Main flow

```
Owner creates Project → Configures AI Config → Creates Module/Feature
→ Uploads raw requirement → Triggers Agent Run
→ Real-time monitor agent status via SSE
→ Receives report per agent → Reviews & Approves → Done
```

---

## 2. Tech Stack & Dependencies

### Backend (Java)

```xml
<!-- pom.xml — core dependencies -->
<parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.0</version>
</parent>

<!-- Vaadin -->
<dependency>
    <groupId>com.vaadin</groupId>
    <artifactId>vaadin-spring-boot-starter</artifactId>
    <version>24.4.0</version>
</dependency>

<!-- Spring Data JPA + PostgreSQL -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-jpa</artifactId>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
</dependency>

<!-- Redis -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-data-redis</artifactId>
</dependency>

<!-- Spring Security -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-security</artifactId>
</dependency>

<!-- File upload / MinIO -->
<dependency>
    <groupId>io.minio</groupId>
    <artifactId>minio</artifactId>
    <version>8.5.7</version>
</dependency>

<!-- Lombok -->
<dependency>
    <groupId>org.projectlombok</groupId>
    <artifactId>lombok</artifactId>
    <optional>true</optional>
</dependency>

<!-- Flyway migrations -->
<dependency>
    <groupId>org.flywaydb</groupId>
    <artifactId>flyway-core</artifactId>
</dependency>
```

### Agent Layer (Python)

```
requirements.txt:
crewai==0.80.0
crewai-tools==0.14.0
langchain-openai==0.2.0
redis==5.0.0
python-dotenv==1.0.0
```

---

## 3. Database Schema (PostgreSQL)

### Flyway migration: `V1__init_schema.sql`

```sql
-- Users / Owners
CREATE TABLE users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    username VARCHAR(100) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(50) DEFAULT 'OWNER',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- AI Configuration (per project)
-- Supports three invocation modes: API (OpenAI-compatible endpoint), CLI (subprocess), SDK (provider SDK)
CREATE TABLE ai_configs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Provider identifier: "openai_compatible" | "openai" | "anthropic" | "ollama_cli" | "llama_cli" | "custom"
    llm_provider VARCHAR(50) NOT NULL DEFAULT 'openai_compatible',
    -- Invocation mode: API | CLI | SDK
    invocation_mode VARCHAR(10) NOT NULL DEFAULT 'API',
    -- Used when invocation_mode = API
    llm_base_url VARCHAR(500) DEFAULT 'http://localhost:1234/v1',
    llm_model_name VARCHAR(200) DEFAULT 'qwen3.5-35b',
    llm_api_key VARCHAR(500),
    -- Used when invocation_mode = CLI (e.g. "ollama run qwen3.5:35b")
    llm_cli_command VARCHAR(1000),
    temperature DECIMAL(3,2) DEFAULT 0.50,
    max_tokens_per_task INTEGER DEFAULT 4096,
    output_language VARCHAR(10) DEFAULT 'en',
    approval_required BOOLEAN DEFAULT TRUE,
    active_agents JSONB DEFAULT '["pm","architect","dev","qa","docs"]',
    tech_stack JSONB DEFAULT '[]',
    coding_style_guide TEXT,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- Projects
CREATE TABLE projects (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name VARCHAR(255) NOT NULL,
    description TEXT,
    owner_id UUID NOT NULL REFERENCES users(id),
    ai_config_id UUID REFERENCES ai_configs(id),
    status VARCHAR(50) DEFAULT 'ACTIVE',
    git_repo_url VARCHAR(500),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- Project context documents (ERD, code samples, API convention, etc.)
CREATE TABLE project_context_docs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    doc_type VARCHAR(100) NOT NULL, -- 'erd', 'code_sample', 'api_convention', 'style_guide', 'other'
    file_name VARCHAR(500) NOT NULL,
    file_path VARCHAR(1000) NOT NULL,
    description TEXT,
    created_at TIMESTAMP DEFAULT NOW()
);

-- Modules / Features
CREATE TABLE modules (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    raw_requirement TEXT,
    req_file_path VARCHAR(1000),
    parsed_stories JSONB,
    status VARCHAR(50) DEFAULT 'DRAFT',
    -- Status values: DRAFT, PENDING_RUN, PM_RUNNING, PM_REVIEW,
    --                ARCHITECT_RUNNING, ARCHITECT_REVIEW,
    --                DEV_RUNNING, DEV_REVIEW,
    --                QA_RUNNING, QA_REVIEW,
    --                DOCS_RUNNING, COMPLETED, FAILED
    current_agent VARCHAR(50),
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- Agent Runs (one per agent per module execution)
CREATE TABLE agent_runs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    module_id UUID NOT NULL REFERENCES modules(id) ON DELETE CASCADE,
    agent_name VARCHAR(50) NOT NULL, -- 'pm', 'architect', 'dev', 'qa', 'docs'
    run_order INTEGER NOT NULL,
    status VARCHAR(50) DEFAULT 'PENDING',
    -- Status: PENDING, RUNNING, COMPLETED, FAILED, AWAITING_APPROVAL, APPROVED, REJECTED
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    duration_seconds INTEGER,
    tokens_used INTEGER DEFAULT 0,
    retry_count INTEGER DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT NOW()
);

-- Agent Events (real-time log of what each agent is doing)
CREATE TABLE agent_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    event_type VARCHAR(50) NOT NULL,
    -- Types: STARTED, THINKING, TOOL_CALL, TOOL_RESULT, INFO, WARNING, ERROR, COMPLETED
    message TEXT NOT NULL,
    payload JSONB,
    severity VARCHAR(20) DEFAULT 'INFO', -- INFO, WARNING, ERROR
    created_at TIMESTAMP DEFAULT NOW()
);

-- Agent Reports (structured output when agent completes)
CREATE TABLE agent_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID UNIQUE NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    summary TEXT NOT NULL,
    deliverables JSONB DEFAULT '[]',
    -- [{type: 'code'|'doc'|'test'|'schema', name: '...', file_path: '...', lines: 0}]
    issues_found JSONB DEFAULT '[]',
    -- [{severity: 'BLOCKING'|'NON_BLOCKING', description: '...', suggested_action: '...'}]
    next_steps JSONB DEFAULT '[]',
    -- [{action: '...', agent: '...', priority: 'HIGH'|'MEDIUM'|'LOW'}]
    owner_decisions_needed JSONB DEFAULT '[]',
    -- [{question: '...', options: [...], impact: '...'}]
    confidence_score DECIMAL(5,2),
    confidence_reason TEXT,
    tokens_used INTEGER,
    duration_seconds INTEGER,
    created_at TIMESTAMP DEFAULT NOW()
);

-- Indexes
CREATE INDEX idx_modules_project_id ON modules(project_id);
CREATE INDEX idx_agent_runs_module_id ON agent_runs(module_id);
CREATE INDEX idx_agent_events_run_id ON agent_events(run_id);
CREATE INDEX idx_agent_events_created_at ON agent_events(created_at);
CREATE INDEX idx_projects_owner_id ON projects(owner_id);
```

---

## 4. Project Structure

```
ai-dev-platform/
├── backend/                          # Spring Boot + Vaadin
│   ├── src/main/java/com/aidevplatform/
│   │   ├── AiDevPlatformApplication.java
│   │   ├── config/
│   │   │   ├── SecurityConfig.java
│   │   │   ├── RedisConfig.java
│   │   │   ├── MinioConfig.java
│   │   │   └── VaadinConfig.java
│   │   ├── domain/
│   │   │   ├── entity/
│   │   │   │   ├── User.java
│   │   │   │   ├── Project.java
│   │   │   │   ├── AiConfig.java
│   │   │   │   ├── Module.java
│   │   │   │   ├── AgentRun.java
│   │   │   │   ├── AgentEvent.java
│   │   │   │   ├── AgentReport.java
│   │   │   │   └── ProjectContextDoc.java
│   │   │   └── enums/
│   │   │       ├── ModuleStatus.java
│   │   │       ├── AgentRunStatus.java
│   │   │       └── AgentEventType.java
│   │   ├── repository/
│   │   │   ├── ProjectRepository.java
│   │   │   ├── ModuleRepository.java
│   │   │   ├── AgentRunRepository.java
│   │   │   ├── AgentEventRepository.java
│   │   │   └── AgentReportRepository.java
│   │   ├── service/
│   │   │   ├── ProjectService.java
│   │   │   ├── ModuleService.java
│   │   │   ├── AgentOrchestrator.java      # Coordinates agent run lifecycle
│   │   │   ├── AgentEventService.java      # Persists and broadcasts events
│   │   │   ├── ReportService.java
│   │   │   ├── FileStorageService.java
│   │   │   └── SseService.java             # SSE push to Vaadin UI
│   │   ├── api/
│   │   │   ├── AgentCallbackController.java  # Receives POSTs from Python agents
│   │   │   └── SseController.java            # SSE subscription endpoint
│   │   └── ui/                             # Vaadin views
│   │       ├── MainLayout.java
│   │       ├── views/
│   │       │   ├── DashboardView.java
│   │       │   ├── ProjectListView.java
│   │       │   ├── ProjectDetailView.java
│   │       │   ├── ProjectSettingsView.java
│   │       │   ├── ModuleManagerView.java
│   │       │   ├── ModuleDetailView.java
│   │       │   ├── AgentMonitorView.java
│   │       │   └── ReportView.java
│   │       └── components/
│   │           ├── AgentStatusCard.java
│   │           ├── AgentLogPanel.java
│   │           ├── ReportCard.java
│   │           ├── FileUploadComponent.java
│   │           └── AiConfigForm.java
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/
│           └── V1__init_schema.sql
│
└── agent/                            # Python CrewAI agent layer
    ├── main.py                       # Entry point — listens on Redis for tasks
    ├── agents/
    │   ├── pm_agent.py
    │   ├── architect_agent.py
    │   ├── dev_agent.py
    │   ├── qa_agent.py
    │   └── docs_agent.py
    ├── tools/
    │   ├── file_tools.py
    │   └── report_tools.py
    ├── crew_runner.py
    └── event_publisher.py            # Publishes events back to Spring Boot
```

---

## 5. Entities (Java)

### BaseEntity.java

```java
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
@Getter @Setter
public abstract class BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(columnDefinition = "uuid")
    private UUID id;

    @CreatedDate
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
```

### Project.java

```java
@Entity
@Table(name = "projects")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Project extends BaseEntity {
    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "owner_id", nullable = false)
    private User owner;

    @OneToOne(cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @JoinColumn(name = "ai_config_id")
    private AiConfig aiConfig;

    @Enumerated(EnumType.STRING)
    private ProjectStatus status = ProjectStatus.ACTIVE;

    private String gitRepoUrl;

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL)
    private List<Module> modules = new ArrayList<>();

    @OneToMany(mappedBy = "project", cascade = CascadeType.ALL)
    private List<ProjectContextDoc> contextDocs = new ArrayList<>();
}
```

### AiConfig.java

```java
@Entity
@Table(name = "ai_configs")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AiConfig extends BaseEntity {

    // --- LLM Provider ---
    // Supported values: "openai_compatible", "openai", "anthropic", "ollama_cli", "llama_cli", "custom"
    private String llmProvider = "openai_compatible";

    // Invocation mode: API | CLI | SDK
    @Enumerated(EnumType.STRING)
    private LlmInvocationMode invocationMode = LlmInvocationMode.API;

    // Used when invocationMode = API: base URL of the OpenAI-compatible endpoint
    // Examples:
    //   Local LM Studio  → http://localhost:1234/v1
    //   Local Ollama     → http://localhost:11434/v1
    //   Alibaba Cloud    → https://dashscope.aliyuncs.com/compatible-mode/v1
    //   OpenAI           → https://api.openai.com/v1
    //   Anthropic        → https://api.anthropic.com (SDK mode preferred)
    private String llmBaseUrl = "http://localhost:1234/v1";

    // Model name exactly as the provider expects it
    // Examples: "qwen3.5-35b", "claude-sonnet-4-6", "gpt-4o", "llama3.3:70b"
    private String llmModelName = "qwen3.5-35b";

    // API key — null for local models that do not require authentication
    private String llmApiKey;

    // Used when invocationMode = CLI: the executable command
    // Examples:
    //   Ollama CLI  → "ollama run qwen3.5:35b"
    //   llama.cpp   → "/usr/local/bin/llama-cli -m /models/qwen3.5.gguf"
    private String llmCliCommand;

    // --- Generation parameters ---
    @Column(precision = 3, scale = 2)
    private BigDecimal temperature = new BigDecimal("0.50");

    private Integer maxTokensPerTask = 4096;

    // --- Agent behaviour ---
    private String outputLanguage = "en"; // "en" or "vi" — language used in agent reports
    private Boolean approvalRequired = true;

    @Column(columnDefinition = "jsonb")
    @Convert(converter = StringListJsonbConverter.class)
    private List<String> activeAgents = List.of("pm", "architect", "dev", "qa", "docs");

    // --- Project context ---
    @Column(columnDefinition = "jsonb")
    @Convert(converter = StringListJsonbConverter.class)
    private List<String> techStack = new ArrayList<>();

    @Column(columnDefinition = "text")
    private String codingStyleGuide;
}
```

### LlmInvocationMode.java (new enum)

```java
/**
 * Defines how the Python agent layer communicates with the LLM.
 *
 * API  — HTTP call to an OpenAI-compatible endpoint (LM Studio, Ollama server,
 *         Alibaba Cloud DashScope, OpenAI, etc.)
 * CLI  — Subprocess call to a local CLI tool (ollama run, llama-cli, etc.)
 * SDK  — Use a provider-specific Python SDK (anthropic, openai, etc.)
 */
public enum LlmInvocationMode {
    API,
    CLI,
    SDK
}
```

### Module.java

```java
@Entity
@Table(name = "modules")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Module extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "project_id", nullable = false)
    private Project project;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @Column(columnDefinition = "text")
    private String rawRequirement;

    private String reqFilePath;

    @Column(columnDefinition = "jsonb")
    @Convert(converter = JsonbConverter.class)
    private List<UserStory> parsedStories = new ArrayList<>();

    @Enumerated(EnumType.STRING)
    private ModuleStatus status = ModuleStatus.DRAFT;

    private String currentAgent;

    @OneToMany(mappedBy = "module", cascade = CascadeType.ALL)
    @OrderBy("runOrder ASC")
    private List<AgentRun> agentRuns = new ArrayList<>();
}
```

### AgentRun.java

```java
@Entity
@Table(name = "agent_runs")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentRun extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "module_id", nullable = false)
    private Module module;

    @Column(nullable = false)
    private String agentName; // "pm" | "architect" | "dev" | "qa" | "docs"

    private Integer runOrder;

    @Enumerated(EnumType.STRING)
    private AgentRunStatus status = AgentRunStatus.PENDING;

    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
    private Integer durationSeconds;
    private Integer tokensUsed = 0;
    private Integer retryCount = 0;
    private String errorMessage;

    @OneToMany(mappedBy = "run", cascade = CascadeType.ALL)
    @OrderBy("createdAt ASC")
    private List<AgentEvent> events = new ArrayList<>();

    @OneToOne(mappedBy = "run", cascade = CascadeType.ALL)
    private AgentReport report;
}
```

### AgentEvent.java

```java
@Entity
@Table(name = "agent_events")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentEvent extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    private AgentRun run;

    @Enumerated(EnumType.STRING)
    private AgentEventType eventType;

    @Column(columnDefinition = "text", nullable = false)
    private String message;

    @Column(columnDefinition = "jsonb")
    @Convert(converter = JsonbConverter.class)
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    private EventSeverity severity = EventSeverity.INFO;
}
```

### AgentReport.java

```java
@Entity
@Table(name = "agent_reports")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class AgentReport extends BaseEntity {
    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "run_id", nullable = false)
    private AgentRun run;

    @Column(columnDefinition = "text", nullable = false)
    private String summary;

    @Column(columnDefinition = "jsonb")
    @Convert(converter = JsonbConverter.class)
    private List<Deliverable> deliverables = new ArrayList<>();

    @Column(columnDefinition = "jsonb")
    @Convert(converter = JsonbConverter.class)
    private List<Issue> issuesFound = new ArrayList<>();

    @Column(columnDefinition = "jsonb")
    @Convert(converter = JsonbConverter.class)
    private List<NextStep> nextSteps = new ArrayList<>();

    @Column(columnDefinition = "jsonb")
    @Convert(converter = JsonbConverter.class)
    private List<OwnerDecision> ownerDecisionsNeeded = new ArrayList<>();

    @Column(precision = 5, scale = 2)
    private BigDecimal confidenceScore;

    private String confidenceReason;
    private Integer tokensUsed;
    private Integer durationSeconds;
}
```

---

## 6. Enums

```java
public enum ModuleStatus {
    DRAFT, PENDING_RUN,
    PM_RUNNING, PM_REVIEW,
    ARCHITECT_RUNNING, ARCHITECT_REVIEW,
    DEV_RUNNING, DEV_REVIEW,
    QA_RUNNING, QA_REVIEW,
    DOCS_RUNNING, DOCS_REVIEW,
    COMPLETED, FAILED
}

public enum AgentRunStatus {
    PENDING, RUNNING, COMPLETED, FAILED,
    AWAITING_APPROVAL, APPROVED, REJECTED
}

public enum AgentEventType {
    STARTED, THINKING, TOOL_CALL, TOOL_RESULT,
    INFO, WARNING, ERROR, COMPLETED
}

public enum EventSeverity { INFO, WARNING, ERROR }
```

---

## 7. Services

### AgentOrchestrator.java

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentOrchestrator {
    private final ModuleRepository moduleRepository;
    private final AgentRunRepository agentRunRepository;
    private final AgentEventService agentEventService;
    private final SseService sseService;
    private final RedisTemplate<String, String> redisTemplate;

    /**
     * Initialises the full agent pipeline for a given module.
     * Creates one AgentRun record per active agent, then dispatches
     * the first task to the Python agent layer via Redis.
     */
    @Async
    public void runAgentPipeline(UUID moduleId) {
        Module module = moduleRepository.findById(moduleId)
            .orElseThrow(() -> new EntityNotFoundException("Module not found: " + moduleId));

        AiConfig config = module.getProject().getAiConfig();
        List<String> activeAgents = config.getActiveAgents();

        // Create AgentRun records in execution order
        List<String> orderedAgents = List.of("pm", "architect", "dev", "qa", "docs");
        int order = 0;
        for (String agentName : orderedAgents) {
            if (activeAgents.contains(agentName)) {
                AgentRun run = AgentRun.builder()
                    .module(module)
                    .agentName(agentName)
                    .runOrder(order++)
                    .status(AgentRunStatus.PENDING)
                    .build();
                agentRunRepository.save(run);
            }
        }

        module.setStatus(ModuleStatus.PENDING_RUN);
        moduleRepository.save(module);

        // Dispatch task payload to Python agent layer via Redis
        AgentTask task = buildAgentTask(module, config);
        String taskJson = objectMapper.writeValueAsString(task);
        redisTemplate.convertAndSend("agent:tasks", taskJson);

        sseService.broadcastModuleUpdate(moduleId, "PIPELINE_STARTED");
    }

    /**
     * Handles the completion callback from a Python agent.
     * Persists the report and either awaits owner approval or
     * automatically triggers the next agent in the pipeline.
     */
    public void handleAgentComplete(AgentCallbackDto callback) {
        AgentRun run = agentRunRepository.findById(callback.getRunId()).orElseThrow();
        run.setStatus(AgentRunStatus.COMPLETED);
        run.setCompletedAt(LocalDateTime.now());
        run.setTokensUsed(callback.getTokensUsed());
        agentRunRepository.save(run);

        AgentReport report = mapToReport(callback.getReport(), run);
        agentReportRepository.save(report);

        AiConfig config = run.getModule().getProject().getAiConfig();
        if (config.getApprovalRequired()) {
            run.setStatus(AgentRunStatus.AWAITING_APPROVAL);
            agentRunRepository.save(run);
            sseService.broadcastAgentAwaitingApproval(run.getId());
        } else {
            triggerNextAgent(run.getModule());
        }
    }

    public void approveRun(UUID runId) {
        AgentRun run = agentRunRepository.findById(runId).orElseThrow();
        run.setStatus(AgentRunStatus.APPROVED);
        agentRunRepository.save(run);
        triggerNextAgent(run.getModule());
    }

    public void rejectRun(UUID runId, String reason) {
        AgentRun run = agentRunRepository.findById(runId).orElseThrow();
        run.setStatus(AgentRunStatus.REJECTED);
        agentRunRepository.save(run);
        sseService.broadcastRunRejected(runId, reason);
    }

    private void triggerNextAgent(Module module) {
        List<AgentRun> pending = agentRunRepository
            .findByModuleIdAndStatusOrderByRunOrder(module.getId(), AgentRunStatus.PENDING);
        if (!pending.isEmpty()) {
            AgentRun next = pending.get(0);
            redisTemplate.convertAndSend("agent:next",
                objectMapper.writeValueAsString(Map.of("runId", next.getId())));
        } else {
            module.setStatus(ModuleStatus.COMPLETED);
            moduleRepository.save(module);
            sseService.broadcastModuleComplete(module.getId());
        }
    }
}
```

### SseService.java

```java
@Service
@RequiredArgsConstructor
@Slf4j
public class SseService {
    // Map<userId, List<SseEmitter>> — supports multiple browser tabs per user
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    public SseEmitter subscribe(String userId) {
        SseEmitter emitter = new SseEmitter(Long.MAX_VALUE);
        emitters.computeIfAbsent(userId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(userId, emitter));
        emitter.onTimeout(() -> removeEmitter(userId, emitter));
        emitter.onError(e -> removeEmitter(userId, emitter));

        return emitter;
    }

    public void broadcastAgentEvent(AgentEvent event) {
        String ownerId = event.getRun().getModule().getProject().getOwner().getId().toString();
        AgentEventDto dto = mapToDto(event);
        sendToUser(ownerId, "agent-event", dto);
    }

    public void broadcastModuleUpdate(UUID moduleId, String eventName) {
        // Resolve owner from module then broadcast
        sendToUser(ownerId, "module-update", Map.of("moduleId", moduleId, "event", eventName));
    }

    private void sendToUser(String userId, String eventName, Object data) {
        List<SseEmitter> userEmitters = emitters.getOrDefault(userId, List.of());
        userEmitters.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event()
                    .name(eventName)
                    .data(objectMapper.writeValueAsString(data)));
            } catch (Exception e) {
                removeEmitter(userId, emitter);
            }
        });
    }
}
```

### AgentEventService.java

```java
@Service
@RequiredArgsConstructor
public class AgentEventService {
    private final AgentEventRepository eventRepository;
    private final SseService sseService;

    /**
     * Persists a new agent event and immediately broadcasts it to
     * the owner's SSE connection so the monitor view updates in real time.
     */
    @Transactional
    public AgentEvent saveAndBroadcast(UUID runId, AgentEventType type,
                                        String message, Map<String, Object> payload) {
        AgentRun run = agentRunRepository.findById(runId).orElseThrow();
        AgentEvent event = AgentEvent.builder()
            .run(run)
            .eventType(type)
            .message(message)
            .payload(payload)
            .severity(resolveSeverity(type))
            .build();

        AgentEvent saved = eventRepository.save(event);
        sseService.broadcastAgentEvent(saved);
        return saved;
    }
}
```

---

## 8. REST API Endpoints (AgentCallbackController)

```java
@RestController
@RequestMapping("/api/internal/agent")
@RequiredArgsConstructor
public class AgentCallbackController {

    /** Receives a real-time event from a Python agent and broadcasts it via SSE. */
    @PostMapping("/event")
    public ResponseEntity<Void> pushEvent(@RequestBody AgentEventRequest req) {
        agentEventService.saveAndBroadcast(
            req.getRunId(), req.getEventType(), req.getMessage(), req.getPayload());
        return ResponseEntity.ok().build();
    }

    /** Receives the completion callback and structured report from a Python agent. */
    @PostMapping("/complete")
    public ResponseEntity<Void> completeRun(@RequestBody AgentCallbackDto callback) {
        agentOrchestrator.handleAgentComplete(callback);
        return ResponseEntity.ok().build();
    }

    /** SSE subscription endpoint — Vaadin UI connects here to receive real-time events. */
    @GetMapping("/sse/{userId}")
    public SseEmitter subscribe(@PathVariable String userId) {
        return sseService.subscribe(userId);
    }
}
```

---

## 9. Vaadin Views

### MainLayout.java

```java
@Layout
@RequiredArgsConstructor
public class MainLayout extends AppLayout {

    @Override
    protected void onAttach(AttachEvent event) {
        super.onAttach(event);
        createHeader();
        createNavigation();
        setupSseListener();
    }

    private void createNavigation() {
        SideNav nav = new SideNav();
        nav.addItem(new SideNavItem("Dashboard", DashboardView.class));
        nav.addItem(new SideNavItem("Projects", ProjectListView.class));
        addToDrawer(nav);
    }

    private void setupSseListener() {
        // Subscribe to SSE endpoint to receive real-time agent events
        // On event received, update the relevant UI component
        String userId = getCurrentUserId();
        // EventSource subscription logic goes here
    }
}
```

### AgentMonitorView.java

```java
@Route(value = "module/:moduleId/monitor", layout = MainLayout.class)
@RequiredArgsConstructor
public class AgentMonitorView extends VerticalLayout implements BeforeEnterObserver {

    private final ModuleService moduleService;
    private final AgentRunRepository agentRunRepository;
    private UUID moduleId;

    // Agent status cards keyed by agent name
    private final Map<String, AgentStatusCard> agentCards = new LinkedHashMap<>();

    // Live event log panel
    private final AgentLogPanel logPanel = new AgentLogPanel();

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        moduleId = UUID.fromString(event.getRouteParameters().get("moduleId").orElseThrow());
        buildUI();
        loadCurrentState();
    }

    private void buildUI() {
        setSizeFull();
        setPadding(true);

        // Header with module name and overall pipeline status
        HorizontalLayout header = buildHeader();

        // Agent status cards — one per agent in the pipeline
        HorizontalLayout agentsRow = new HorizontalLayout();
        agentsRow.setWidthFull();
        for (String agent : List.of("pm", "architect", "dev", "qa", "docs")) {
            AgentStatusCard card = new AgentStatusCard(agent);
            agentCards.put(agent, card);
            agentsRow.add(card);
        }

        // Tab sheet: Monitor | Event Log | Reports
        TabSheet tabs = new TabSheet();
        tabs.add("Agent Monitor", agentsRow);
        tabs.add("Event Log", logPanel);
        tabs.add("Reports", new ReportPanel(moduleId));
        tabs.setSizeFull();

        add(header, tabs);
    }

    /**
     * Called by SseService when a new agent event arrives.
     * Must run inside UI.access() because it originates from a background thread.
     */
    public void onAgentEvent(AgentEventDto event) {
        getUI().ifPresent(ui -> ui.access(() -> {
            AgentStatusCard card = agentCards.get(event.getAgentName());
            if (card != null) {
                card.updateStatus(event);
            }
            logPanel.appendEvent(event);
        }));
    }
}
```

### AgentStatusCard.java (Vaadin component)

```java
public class AgentStatusCard extends VerticalLayout {
    private final String agentName;
    private final Span statusBadge;
    private final Span currentTaskLabel;
    private final ProgressBar progressBar;
    private final Span timeLabel;

    public AgentStatusCard(String agentName) {
        this.agentName = agentName;
        addClassName("agent-status-card");
        setWidth("200px");
        setPadding(true);
        getStyle().set("border", "1px solid var(--lumo-contrast-10pct)");
        getStyle().set("border-radius", "var(--lumo-border-radius-m)");

        Span title = new Span(resolveDisplayName(agentName));
        title.getStyle().set("font-weight", "500");

        statusBadge = new Span("Idle");
        statusBadge.addClassName("status-badge-idle");

        currentTaskLabel = new Span("—");
        currentTaskLabel.getStyle().set("font-size", "12px")
            .set("color", "var(--lumo-secondary-text-color)");

        progressBar = new ProgressBar();
        progressBar.setIndeterminate(false);
        progressBar.setValue(0);
        progressBar.setVisible(false);

        timeLabel = new Span("—");
        timeLabel.getStyle().set("font-size", "11px")
            .set("color", "var(--lumo-tertiary-text-color)");

        add(title, statusBadge, currentTaskLabel, progressBar, timeLabel);
    }

    public void updateStatus(AgentEventDto event) {
        switch (event.getEventType()) {
            case "STARTED" -> {
                setStatus("Running", "status-badge-running");
                progressBar.setIndeterminate(true);
                progressBar.setVisible(true);
            }
            case "THINKING" -> {
                currentTaskLabel.setText("Analysing...");
            }
            case "TOOL_CALL" -> {
                currentTaskLabel.setText(truncate(event.getMessage(), 50));
            }
            case "COMPLETED" -> {
                setStatus("Done", "status-badge-done");
                progressBar.setVisible(false);
            }
            case "ERROR" -> {
                setStatus("Error", "status-badge-error");
                progressBar.setVisible(false);
            }
        }
    }

    private void setStatus(String text, String cssClass) {
        statusBadge.setText(text);
        statusBadge.removeClassNames("status-badge-idle", "status-badge-running",
            "status-badge-done", "status-badge-error", "status-badge-waiting");
        statusBadge.addClassName(cssClass);
    }
}
```

### ReportCard.java (Vaadin component)

```java
public class ReportCard extends VerticalLayout {

    public ReportCard(AgentReport report) {
        setPadding(true);
        getStyle().set("border", "1px solid var(--lumo-contrast-10pct)")
            .set("border-radius", "var(--lumo-border-radius-m)");

        // Agent name + confidence score header
        HorizontalLayout header = buildHeader(report);

        // Summary
        Paragraph summary = new Paragraph(report.getSummary());
        summary.getStyle().set("font-size", "14px");

        // Deliverables
        VerticalLayout deliverablesSection = buildDeliverablesSection(report.getDeliverables());

        // Show owner decisions section highlighted as warning if any decisions are pending
        if (!report.getOwnerDecisionsNeeded().isEmpty()) {
            VerticalLayout decisionsSection = buildDecisionsSection(report.getOwnerDecisionsNeeded());
            add(header, summary, deliverablesSection, decisionsSection);
        } else {
            add(header, summary, deliverablesSection);
        }

        // Show Approve / Reject buttons when run is awaiting owner approval
        if (report.getRun().getStatus() == AgentRunStatus.AWAITING_APPROVAL) {
            add(buildApprovalButtons(report.getRun().getId()));
        }
    }

    private HorizontalLayout buildHeader(AgentReport report) {
        Span agentName = new Span(resolveDisplayName(report.getRun().getAgentName()));
        agentName.getStyle().set("font-weight", "500").set("font-size", "15px");

        // Confidence score bar
        Span confidenceLabel = new Span(report.getConfidenceScore() + "%");
        confidenceLabel.getStyle().set("font-size", "13px");

        ProgressBar confidenceBar = new ProgressBar();
        confidenceBar.setValue(report.getConfidenceScore().doubleValue() / 100);
        confidenceBar.setWidth("120px");

        // Color code confidence
        if (report.getConfidenceScore().compareTo(new BigDecimal("70")) < 0) {
            confidenceBar.addThemeVariants(ProgressBarVariant.LUMO_ERROR);
        } else if (report.getConfidenceScore().compareTo(new BigDecimal("85")) < 0) {
            confidenceBar.addThemeVariants(ProgressBarVariant.LUMO_CONTRAST);
        }

        HorizontalLayout header = new HorizontalLayout(agentName, confidenceBar, confidenceLabel);
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        return header;
    }

    private HorizontalLayout buildApprovalButtons(UUID runId) {
        Button approveBtn = new Button("Approve", e -> agentOrchestrator.approveRun(runId));
        approveBtn.addThemeVariants(ButtonVariant.LUMO_SUCCESS);

        Button rejectBtn = new Button("Reject", e -> openRejectDialog(runId));
        rejectBtn.addThemeVariants(ButtonVariant.LUMO_ERROR, ButtonVariant.LUMO_TERTIARY);

        return new HorizontalLayout(approveBtn, rejectBtn);
    }
}
```

---

## 10. Python Agent Layer

### event_publisher.py

```python
import redis
import requests
import json
from datetime import datetime

BACKEND_URL = "http://localhost:8080/api/internal/agent"

def push_event(run_id: str, event_type: str, message: str, payload: dict = None):
    """Send a real-time event to the Spring Boot backend."""
    data = {
        "runId": run_id,
        "eventType": event_type,
        "message": message,
        "payload": payload or {},
        "timestamp": datetime.now().isoformat()
    }
    requests.post(f"{BACKEND_URL}/event", json=data, timeout=5)

def complete_run(run_id: str, report: dict):
    """Notify the backend that this agent run has finished and submit the structured report."""
    data = {
        "runId": run_id,
        "report": report,
        "tokensUsed": report.get("tokens_used", 0)
    }
    requests.post(f"{BACKEND_URL}/complete", json=data, timeout=30)
```

### crew_runner.py

```python
import subprocess
import json
from crewai import Agent, Task, Crew, LLM
from event_publisher import push_event, complete_run


def build_llm(config: dict) -> LLM:
    """
    Build the LLM instance based on the project's AI config.
    Supports three invocation modes: API, CLI, SDK.
    """
    mode = config.get("invocation_mode", "API").upper()

    if mode == "API":
        # OpenAI-compatible HTTP endpoint
        # Works with: LM Studio, Ollama server, Alibaba Cloud DashScope,
        #             OpenAI, any OpenAI-compatible provider
        return LLM(
            model=f"openai/{config['llm_model_name']}",
            base_url=config["llm_base_url"],
            api_key=config.get("llm_api_key") or "not-required",
            temperature=float(config.get("temperature", 0.5)),
            max_tokens=int(config.get("max_tokens_per_task", 4096)),
        )

    elif mode == "SDK":
        # Provider-specific SDK — determine from llm_provider
        provider = config.get("llm_provider", "").lower()

        if provider == "anthropic":
            return LLM(
                model=f"anthropic/{config['llm_model_name']}",
                api_key=config["llm_api_key"],
                temperature=float(config.get("temperature", 0.5)),
                max_tokens=int(config.get("max_tokens_per_task", 4096)),
            )
        elif provider == "openai":
            return LLM(
                model=config["llm_model_name"],
                api_key=config["llm_api_key"],
                temperature=float(config.get("temperature", 0.5)),
                max_tokens=int(config.get("max_tokens_per_task", 4096)),
            )
        else:
            raise ValueError(f"Unsupported SDK provider: {provider}")

    elif mode == "CLI":
        # Local CLI subprocess — wrap output as a callable LLM
        # Used for: ollama run, llama-cli, etc.
        return CliLlm(
            cli_command=config["llm_cli_command"],
            max_tokens=int(config.get("max_tokens_per_task", 4096)),
        )

    else:
        raise ValueError(f"Unknown invocation mode: {mode}")


class CliLlm:
    """
    Adapter that wraps a local CLI command to behave like an LLM object
    compatible with CrewAI's Agent interface.

    Example cli_command: "ollama run qwen3.5:35b"
    """

    def __init__(self, cli_command: str, max_tokens: int = 4096):
        self.cli_command = cli_command
        self.max_tokens = max_tokens

    def call(self, messages: list) -> str:
        # Build prompt from messages list
        prompt = "\n".join(
            f"{m['role'].upper()}: {m['content']}" for m in messages
        )
        cmd = self.cli_command.split() + [prompt]
        result = subprocess.run(
            cmd,
            capture_output=True,
            text=True,
            timeout=300,
        )
        if result.returncode != 0:
            raise RuntimeError(f"CLI LLM error: {result.stderr}")
        return result.stdout.strip()


def run_agent_pipeline(task_config: dict):
    """
    Execute a single agent for one module run.

    task_config fields:
      run_id             : UUID of AgentRun record
      module_id          : UUID of Module record
      agent_name         : "pm" | "architect" | "dev" | "qa" | "docs"
      invocation_mode    : "API" | "CLI" | "SDK"
      llm_provider       : provider identifier string
      llm_base_url       : base URL (API mode)
      llm_model_name     : model name as provider expects it
      llm_api_key        : API key (null for local)
      llm_cli_command    : CLI command string (CLI mode)
      temperature        : float
      max_tokens_per_task: int
      output_language    : "en" | "vi"
      raw_requirement    : raw text from customer
      tech_stack         : list of strings
      coding_style_guide : string
      context_docs       : list of file paths
      previous_outputs   : dict of outputs from prior agents
    """
    run_id = task_config["run_id"]
    agent_name = task_config["agent_name"]

    push_event(run_id, "STARTED", f"Agent '{agent_name}' started")

    try:
        llm = build_llm(task_config)
        project_context = _build_project_context(task_config)
        agent = _build_agent(agent_name, llm, project_context, run_id)
        task = _build_task(agent_name, task_config, agent)

        crew = Crew(agents=[agent], tasks=[task], verbose=True)
        result = crew.kickoff()

        report = _parse_result_to_report(result, agent_name, task_config)
        push_event(run_id, "COMPLETED", f"Agent '{agent_name}' completed successfully")
        complete_run(run_id, report)

    except Exception as exc:
        push_event(run_id, "ERROR", f"Agent '{agent_name}' failed: {str(exc)}")
        raise


def _build_project_context(config: dict) -> str:
    """Assemble a context string injected into every agent's backstory."""
    parts = [
        f"Tech stack: {', '.join(config.get('tech_stack', []))}",
        f"Output language for reports: {config.get('output_language', 'en')}",
    ]
    if config.get("coding_style_guide"):
        parts.append(f"\nCoding style guide:\n{config['coding_style_guide']}")
    if config.get("previous_outputs"):
        parts.append(
            f"\nOutputs from previous agents:\n"
            f"{json.dumps(config['previous_outputs'], ensure_ascii=False, indent=2)}"
        )
    return "\n".join(parts)


AGENT_DEFINITIONS = {
    "pm": {
        "role": "Product Manager",
        "goal": "Analyse raw customer requirements and produce clear user stories with acceptance criteria",
        "backstory_template": "You are a senior Product Manager experienced in translating vague customer needs into actionable development tasks. {context}",
    },
    "architect": {
        "role": "Software Architect",
        "goal": "Design the database schema, API contract, and system architecture based on user stories",
        "backstory_template": "You are a Software Architect specialising in scalable, maintainable systems. {context}",
    },
    "dev": {
        "role": "Senior Developer",
        "goal": "Implement production-ready code according to the architecture spec, including unit tests",
        "backstory_template": "You are a full-stack Senior Developer who writes clean, well-tested code. {context}",
    },
    "qa": {
        "role": "QA Engineer",
        "goal": "Review the implementation for bugs, missing edge cases, and security vulnerabilities",
        "backstory_template": "You are a QA Engineer focused on test automation and security. {context}",
    },
    "docs": {
        "role": "Technical Writer",
        "goal": "Write comprehensive technical documentation for all delivered artifacts",
        "backstory_template": "You are a Technical Writer who produces clear API docs, README files, and changelogs. {context}",
    },
}


def _build_agent(agent_name: str, llm, project_context: str, run_id: str) -> Agent:
    defn = AGENT_DEFINITIONS[agent_name]
    backstory = defn["backstory_template"].format(context=project_context)
    return Agent(
        role=defn["role"],
        goal=defn["goal"],
        backstory=backstory,
        llm=llm,
        verbose=True,
        step_callback=lambda step: push_event(
            run_id, "THINKING", str(step)[:500]
        ),
    )


def _build_task(agent_name: str, config: dict, agent: Agent) -> Task:
    requirement = config.get("raw_requirement", "")
    descriptions = {
        "pm": (
            f"Analyse the following raw customer requirement and produce:\n"
            f"1. A numbered list of user stories in the format: "
            f"   'As a <role>, I want <feature>, so that <benefit>'\n"
            f"2. Acceptance criteria for each story\n"
            f"3. A list of open questions or ambiguities that need owner clarification\n\n"
            f"RAW REQUIREMENT:\n{requirement}"
        ),
        "architect": (
            "Based on the user stories provided, design:\n"
            "1. PostgreSQL database schema (CREATE TABLE statements)\n"
            "2. REST API endpoints (method, path, request/response shape)\n"
            "3. High-level component diagram in text form\n"
            "4. Any architectural decisions or trade-offs"
        ),
        "dev": (
            "Implement the feature according to the architecture spec. Produce:\n"
            "1. All required source files with full implementation\n"
            "2. Unit tests for core business logic\n"
            "3. A brief summary of what was implemented and any deviations from the spec"
        ),
        "qa": (
            "Review the implemented code and produce:\n"
            "1. List of bugs found (severity: CRITICAL / MAJOR / MINOR)\n"
            "2. Missing edge cases or test scenarios\n"
            "3. Security concerns (OWASP top 10 applicable items)\n"
            "4. Overall quality assessment"
        ),
        "docs": (
            "Write technical documentation for all delivered artifacts:\n"
            "1. README with setup and usage instructions\n"
            "2. API documentation (endpoint descriptions, request/response examples)\n"
            "3. Database schema documentation\n"
            "4. Changelog entry for this module"
        ),
    }
    return Task(
        description=descriptions[agent_name],
        agent=agent,
        expected_output="A structured markdown document following the report format defined in the spec",
    )


def _parse_result_to_report(result, agent_name: str, config: dict) -> dict:
    """Convert CrewAI output into the structured report format expected by the backend."""
    return {
        "summary": _extract_summary(result),
        "deliverables": _extract_deliverables(result, agent_name),
        "issues_found": _extract_issues(result),
        "next_steps": _build_next_steps(agent_name),
        "owner_decisions_needed": _extract_decisions_needed(result),
        "confidence_score": _calculate_confidence(result, config),
        "confidence_reason": _extract_confidence_reason(result),
        "tokens_used": getattr(result, "token_usage", {}).get("total_tokens", 0),
    }
```

---

## 11. application.yml

```yaml
spring:
  application:
    name: ai-dev-platform

  datasource:
    url: jdbc:postgresql://localhost:5432/aidevplatform
    username: ${DB_USERNAME:postgres}
    password: ${DB_PASSWORD:postgres}
    driver-class-name: org.postgresql.Driver

  jpa:
    hibernate:
      ddl-auto: validate
    show-sql: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect
        format_sql: true

  flyway:
    enabled: true
    locations: classpath:db/migration

  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

  servlet:
    multipart:
      max-file-size: 50MB
      max-request-size: 50MB

vaadin:
  launch-browser: false
  allowed-packages: com.aidevplatform

# MinIO file storage
minio:
  endpoint: ${MINIO_ENDPOINT:http://localhost:9000}
  access-key: ${MINIO_ACCESS_KEY:minioadmin}
  secret-key: ${MINIO_SECRET_KEY:minioadmin}
  bucket-name: aidevplatform

# Agent Python service
agent:
  service-url: ${AGENT_SERVICE_URL:http://localhost:8001}
  internal-api-key: ${AGENT_API_KEY:dev-secret}

server:
  port: 8080
```

---

## 12. Docker Compose (development)

```yaml
version: '3.8'
services:
  postgres:
    image: postgres:16
    environment:
      POSTGRES_DB: aidevplatform
      POSTGRES_USER: postgres
      POSTGRES_PASSWORD: postgres
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"

  minio:
    image: minio/minio
    command: server /data --console-address ":9001"
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    ports:
      - "9000:9000"
      - "9001:9001"
    volumes:
      - minio_data:/data

volumes:
  postgres_data:
  minio_data:
```

---

## 13. Report Format — Standard JSON contract

Agents must return reports in exactly this structure. All string values must be in English (unless `output_language` is set to `"vi"` in the project's AI config, in which case `summary`, `description`, `question`, `impact` fields may be in Vietnamese — but field names stay English).

```json
{
  "summary": "Analysed authentication module requirements. Produced 8 user stories and 23 acceptance criteria. Requirement is clear and sufficiently detailed for implementation.",
  "deliverables": [
    {
      "type": "doc",
      "name": "user_stories.md",
      "file_path": "/outputs/auth/user_stories.md",
      "description": "8 user stories with acceptance criteria",
      "lines": 120
    },
    {
      "type": "code",
      "name": "AuthController.java",
      "file_path": "/outputs/auth/AuthController.java",
      "description": "REST controller for authentication endpoints",
      "lines": 85
    }
  ],
  "issues_found": [
    {
      "severity": "BLOCKING",
      "description": "Requirement does not mention MFA",
      "suggested_action": "Confirm with owner whether MFA is in scope"
    },
    {
      "severity": "NON_BLOCKING",
      "description": "Refresh token expiry not specified",
      "suggested_action": "Default to 7 days — configurable post-launch"
    }
  ],
  "next_steps": [
    {
      "action": "Architect Agent to design DB schema for users and sessions tables",
      "agent": "architect",
      "priority": "HIGH"
    }
  ],
  "owner_decisions_needed": [
    {
      "question": "Is Multi-Factor Authentication (MFA) required?",
      "options": ["Yes — add to scope", "No — exclude from scope"],
      "impact": "Affects DB schema, session management, and implementation complexity"
    }
  ],
  "confidence_score": 87.5,
  "confidence_reason": "Requirement is clear and tech stack is familiar. Gap: MFA scope and session strategy are undefined.",
  "tokens_used": 3240
}
```

---

## 14. Vaadin CSS (styles.css)

```css
/* Agent status badges */
.status-badge-idle {
    background: var(--lumo-contrast-5pct);
    color: var(--lumo-secondary-text-color);
    padding: 2px 10px;
    border-radius: 999px;
    font-size: 12px;
}

.status-badge-running {
    background: #E6F1FB;
    color: #0C447C;
    padding: 2px 10px;
    border-radius: 999px;
    font-size: 12px;
    font-weight: 500;
}

.status-badge-done {
    background: #EAF3DE;
    color: #27500A;
    padding: 2px 10px;
    border-radius: 999px;
    font-size: 12px;
    font-weight: 500;
}

.status-badge-waiting {
    background: #FAEEDA;
    color: #633806;
    padding: 2px 10px;
    border-radius: 999px;
    font-size: 12px;
}

.status-badge-error {
    background: #FCEBEB;
    color: #791F1F;
    padding: 2px 10px;
    border-radius: 999px;
    font-size: 12px;
    font-weight: 500;
}

/* Agent log panel */
.agent-log-panel {
    background: #1a1a1a;
    color: #9FE1CB;
    font-family: monospace;
    font-size: 12px;
    padding: 12px;
    border-radius: 8px;
    line-height: 1.8;
    overflow-y: auto;
    max-height: 400px;
}

.log-timestamp { color: #5F5E5A; }
.log-agent-name { color: #7F77DD; }
.log-thinking { color: #85B7EB; }
.log-warning { color: #FAC775; }
.log-error { color: #F09595; }
.log-success { color: #97C459; }

/* Agent status card */
.agent-status-card {
    min-width: 180px;
    flex: 1;
}

/* Report card */
.report-blocking-issue {
    border-left: 3px solid var(--lumo-error-color);
    padding-left: 8px;
}
```

---

## 15. Implementation Order

Claude Code must follow this sequence to avoid missing dependencies:

1. Set up the Spring Boot + Vaadin project with the `pom.xml` defined above
2. Create Flyway migration `V1__init_schema.sql`
3. Implement all Entity classes with correct JPA annotations (include `LlmInvocationMode` enum)
4. Implement Repository interfaces
5. Implement `SseService` — Vaadin requires this for real-time push
6. Implement `AgentEventService`
7. Implement `AgentOrchestrator` — includes approval gate logic
8. Implement `AgentCallbackController` — REST endpoints consumed by Python agents
9. Implement Vaadin `MainLayout`
10. Implement `ProjectListView` and `ProjectDetailView`
11. Implement `ProjectSettingsView` with `AiConfigForm` component — must expose all three invocation modes (API / CLI / SDK) as selectable options with conditional fields
12. Implement `ModuleManagerView` with drag-and-drop file upload
13. Implement `AgentMonitorView` + `AgentStatusCard` + `AgentLogPanel`
14. Implement `ReportView` + `ReportCard` component with approve/reject actions
15. Set up Python agent layer: `event_publisher.py` → `crew_runner.py` → individual agent files
16. Docker Compose for local development
17. Wire SSE from backend to Vaadin UI — all agent events must propagate to the monitor view in real time

---

## 16. Important notes for Claude Code

- **SSE thread safety in Vaadin**: Always use `UI.getCurrent().access(() -> { ... })` when updating Vaadin components from a background thread. Omitting this causes silent UI freeze.
- **JSONB columns**: Implement a custom `StringListJsonbConverter` that implements `AttributeConverter<List<String>, String>` using Jackson. Apply it consistently to all `JSONB` columns.
- **Agent communication**: Python agents call the Spring Boot REST API to push events and reports. They must never write directly to the database.
- **File upload**: Raw requirement files are stored in MinIO. The file path is saved to `modules.req_file_path`; the extracted text content is saved to `modules.raw_requirement` for agents to read.
- **Approval gate**: When `approval_required = true`, after an agent run completes set `agent_runs.status = AWAITING_APPROVAL` and notify the owner via SSE. The next agent only starts after the owner clicks Approve.
- **LLM provider switching**: `AiConfigForm` must allow the owner to change `invocation_mode`, `llm_provider`, `llm_model_name`, `llm_base_url`, and `llm_cli_command` at any time. Changes take effect on the next agent run — no restart required.
- **CLI mode safety**: When executing `llm_cli_command` via subprocess, always sanitise input and enforce a timeout (default 300 s). Never pass raw user input directly to the shell.
- **Project context in tasks**: When building the agent task payload, always include `tech_stack`, `coding_style_guide`, `previous_outputs` from completed prior agents, and any uploaded context documents. This context is what makes agents generate code that fits the actual project.
- **Language in code**: All code, comments, log messages, variable names, and UI labels in source files must be in English. The `output_language` setting in `AiConfig` only controls the natural-language content of agent reports — not source code.

---

## 17. AiConfigForm — LLM provider switching UI

`AiConfigForm` is the most critical UI component because it controls how the agent layer communicates with the LLM. It must expose all configuration fields with conditional visibility based on the selected invocation mode.

### Field visibility rules

| Field | Shown when |
|---|---|
| `llmProvider` (select) | Always |
| `invocationMode` (radio: API / CLI / SDK) | Always |
| `llmBaseUrl` (text) | `invocationMode == API` |
| `llmModelName` (text) | `invocationMode == API` or `SDK` |
| `llmApiKey` (password) | `invocationMode == API` (optional) or `SDK` (required) |
| `llmCliCommand` (text) | `invocationMode == CLI` |
| `temperature` (slider 0–1) | Always |
| `maxTokensPerTask` (number) | Always |
| `outputLanguage` (select: en / vi) | Always |
| `approvalRequired` (toggle) | Always |
| `activeAgents` (multi-checkbox) | Always |
| `techStack` (tag input) | Always |
| `codingStyleGuide` (textarea) | Always |

### AiConfigForm.java

```java
/**
 * Form component for editing per-project AI configuration.
 * Renders conditional fields based on the selected LLmInvocationMode.
 * Can be embedded in ProjectSettingsView or shown as a dialog.
 */
public class AiConfigForm extends FormLayout {

    private final Select<String> llmProvider = new Select<>();
    private final RadioButtonGroup<LlmInvocationMode> invocationMode = new RadioButtonGroup<>();
    private final TextField llmBaseUrl = new TextField("Base URL");
    private final TextField llmModelName = new TextField("Model name");
    private final PasswordField llmApiKey = new PasswordField("API key");
    private final TextField llmCliCommand = new TextField("CLI command");
    private final Slider temperature = new Slider("Temperature", 0, 1);
    private final IntegerField maxTokensPerTask = new IntegerField("Max tokens per task");
    private final Select<String> outputLanguage = new Select<>();
    private final Checkbox approvalRequired = new Checkbox("Require owner approval between agents");
    private final CheckboxGroup<String> activeAgents = new CheckboxGroup<>();

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

        invocationMode.setLabel("Invocation mode");
        invocationMode.setItems(LlmInvocationMode.values());
        invocationMode.setValue(LlmInvocationMode.API);
        invocationMode.setItemLabelGenerator(mode -> switch (mode) {
            case API -> "API — OpenAI-compatible HTTP endpoint";
            case CLI -> "CLI — Local subprocess (ollama run, llama-cli...)";
            case SDK -> "SDK — Provider-specific Python SDK";
        });

        llmBaseUrl.setPlaceholder("http://localhost:1234/v1");
        llmBaseUrl.setHelperText("Base URL of the OpenAI-compatible endpoint");

        llmModelName.setPlaceholder("qwen3.5-35b");

        llmApiKey.setPlaceholder("Leave empty for local models");

        llmCliCommand.setPlaceholder("ollama run qwen3.5:35b");
        llmCliCommand.setHelperText("Full CLI command — model name must be included");

        outputLanguage.setLabel("Report language");
        outputLanguage.setItems("en", "vi");
        outputLanguage.setValue("en");

        activeAgents.setLabel("Active agents");
        activeAgents.setItems("pm", "architect", "dev", "qa", "docs");
        activeAgents.setValue(Set.of("pm", "architect", "dev", "qa", "docs"));
        activeAgents.setItemLabelGenerator(agent -> switch (agent) {
            case "pm" -> "PM — Requirement analysis";
            case "architect" -> "Architect — System design";
            case "dev" -> "Dev — Code generation";
            case "qa" -> "QA — Testing & review";
            case "docs" -> "Docs — Documentation";
            default -> agent;
        });

        add(llmProvider, invocationMode, llmBaseUrl, llmModelName,
            llmApiKey, llmCliCommand, outputLanguage, approvalRequired,
            activeAgents);
    }

    /** Show/hide fields whenever the invocation mode changes. */
    private void bindModeVisibility() {
        invocationMode.addValueChangeListener(e -> updateFieldVisibility(e.getValue()));
        updateFieldVisibility(LlmInvocationMode.API); // initial state
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
        llmProvider.setValue(config.getLlmProvider());
        invocationMode.setValue(config.getInvocationMode());
        llmBaseUrl.setValue(Optional.ofNullable(config.getLlmBaseUrl()).orElse(""));
        llmModelName.setValue(Optional.ofNullable(config.getLlmModelName()).orElse(""));
        llmApiKey.setValue(Optional.ofNullable(config.getLlmApiKey()).orElse(""));
        llmCliCommand.setValue(Optional.ofNullable(config.getLlmCliCommand()).orElse(""));
        outputLanguage.setValue(Optional.ofNullable(config.getOutputLanguage()).orElse("en"));
        approvalRequired.setValue(Boolean.TRUE.equals(config.getApprovalRequired()));
        activeAgents.setValue(new HashSet<>(
            Optional.ofNullable(config.getActiveAgents()).orElse(List.of())
        ));
    }

    /** Write form values back into an AiConfig entity. */
    public void writeTo(AiConfig config) {
        config.setLlmProvider(llmProvider.getValue());
        config.setInvocationMode(invocationMode.getValue());
        config.setLlmBaseUrl(llmBaseUrl.getValue());
        config.setLlmModelName(llmModelName.getValue());
        config.setLlmApiKey(llmApiKey.getValue().isBlank() ? null : llmApiKey.getValue());
        config.setLlmCliCommand(llmCliCommand.getValue().isBlank() ? null : llmCliCommand.getValue());
        config.setOutputLanguage(outputLanguage.getValue());
        config.setApprovalRequired(approvalRequired.getValue());
        config.setActiveAgents(new ArrayList<>(activeAgents.getValue()));
    }
}
```

### Provider quick-reference for owners

| Provider | Mode | Base URL | Model name example |
|---|---|---|---|
| LM Studio (local) | API | `http://localhost:1234/v1` | `qwen3.5-35b` |
| Ollama server (local) | API | `http://localhost:11434/v1` | `qwen3.5:35b` |
| Ollama CLI (local) | CLI | — | command: `ollama run qwen3.5:35b` |
| Alibaba Cloud | API | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen-max` |
| OpenAI | SDK | — | `gpt-4o` |
| Anthropic | SDK | — | `claude-sonnet-4-6` |
| llama.cpp | CLI | — | command: `/usr/local/bin/llama-cli -m /models/model.gguf` |

