-- Complete schema for AI Dev Platform
-- Version: Final with all entities including agent_sessions

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
    workspace_path TEXT,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- Project context documents (ERD, code samples, API convention, etc.)
CREATE TABLE project_context_docs (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    doc_type VARCHAR(100) NOT NULL,
    file_name VARCHAR(500) NOT NULL,
    file_path VARCHAR(1000) NOT NULL,
    description TEXT,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
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
    agent_name VARCHAR(50) NOT NULL,
    run_order INTEGER NOT NULL,
    status VARCHAR(50) DEFAULT 'PENDING',
    -- Status: PENDING, RUNNING, COMPLETED, FAILED, AWAITING_APPROVAL, APPROVED, REJECTED, TERMINATED
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    duration_seconds INTEGER,
    tokens_used INTEGER DEFAULT 0,
    retry_count INTEGER DEFAULT 0,
    error_message TEXT,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW(),
    -- Prevent duplicate agent runs
    UNIQUE (module_id, agent_name)
);

-- Agent Events (real-time log of what each agent is doing)
CREATE TABLE agent_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    event_type VARCHAR(50) NOT NULL,
    -- Types: STARTED, THINKING, TOOL_CALL, TOOL_RESULT, INFO, WARNING, ERROR, COMPLETED
    message TEXT NOT NULL,
    payload JSONB,
    severity VARCHAR(20) DEFAULT 'INFO',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- Agent Reports (structured output when agent completes)
CREATE TABLE agent_reports (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID UNIQUE NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    summary TEXT NOT NULL,
    deliverables JSONB DEFAULT '[]',
    issues_found JSONB DEFAULT '[]',
    next_steps JSONB DEFAULT '[]',
    owner_decisions_needed JSONB DEFAULT '[]',
    confidence_score DECIMAL(5,2),
    confidence_reason TEXT,
    tokens_used INTEGER,
    duration_seconds INTEGER,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

-- Agent Sessions - 3-tier memory system (Tier 2: Session Summary)
-- Stores LLM-compressed summaries of agent work and design reasoning
CREATE TABLE agent_sessions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    module_id UUID NOT NULL REFERENCES modules(id) ON DELETE CASCADE,
    run_id UUID UNIQUE NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    summary TEXT NOT NULL,
    metadata JSONB,
    reasoning_summary TEXT,
    last_message_at TIMESTAMP,
    message_count INTEGER,
    status VARCHAR(50) DEFAULT 'ACTIVE',
    -- Status (VARCHAR for @Enumerated(EnumType.STRING)): ACTIVE, COMPRESSED, ARCHIVED
    created_at TIMESTAMP DEFAULT NOW()
);

-- Seed a default admin user for development
-- Password is 'admin' hashed with BCrypt (cost 10)
-- Change this before production deployment
INSERT INTO users (id, username, email, password_hash, role)
VALUES (
    '00000000-0000-0000-0000-000000000001',
    'admin',
    'admin@aidevplatform.local',
    '$2a$10$ydQaIy2yRAJrArHvvshx.eMhFTHvD6iFRqDUgXhS.soAruTWKXrgm',
    'OWNER'
) ON CONFLICT (username) DO NOTHING;

-- Indexes
CREATE INDEX idx_modules_project_id ON modules(project_id);
CREATE INDEX idx_agent_runs_module_id ON agent_runs(module_id);
CREATE INDEX idx_agent_runs_status ON agent_runs(status);
CREATE INDEX idx_agent_events_run_id ON agent_events(run_id);
CREATE INDEX idx_agent_events_created_at ON agent_events(created_at);
CREATE INDEX idx_agent_reports_run_id ON agent_reports(run_id);
CREATE INDEX idx_projects_owner_id ON projects(owner_id);
CREATE INDEX idx_projects_status ON projects(status);
CREATE INDEX idx_agent_sessions_module_id ON agent_sessions(module_id);
CREATE INDEX idx_agent_sessions_run_id ON agent_sessions(run_id);
CREATE INDEX idx_agent_sessions_status ON agent_sessions(status);
CREATE INDEX idx_agent_sessions_last_message_at ON agent_sessions(last_message_at);
