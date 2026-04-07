-- Schema for AgentOrchestratorWorkflowTest

CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY,
    username VARCHAR(255) UNIQUE NOT NULL,
    email VARCHAR(255) UNIQUE NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL DEFAULT 'OWNER',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS projects (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    description TEXT,
    owner_id UUID NOT NULL REFERENCES users(id),
    ai_config_id UUID REFERENCES ai_configs(id),
    status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    git_repo_url TEXT,
    workspace_path TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS ai_configs (
    id UUID PRIMARY KEY,
    llm_provider VARCHAR(100) NOT NULL DEFAULT 'openai_compatible',
    invocation_mode VARCHAR(50) NOT NULL DEFAULT 'API',
    llm_base_url TEXT,
    llm_model_name VARCHAR(255),
    llm_api_key TEXT,
    llm_cli_command TEXT,
    temperature DECIMAL(3,2) NOT NULL DEFAULT 0.50,
    max_tokens_per_task INTEGER NOT NULL DEFAULT 4096,
    output_language VARCHAR(10) NOT NULL DEFAULT 'en',
    approval_required BOOLEAN NOT NULL DEFAULT true,
    active_agents JSONB NOT NULL DEFAULT '["pm","architect","dev","qa","docs"]',
    tech_stack JSONB NOT NULL DEFAULT '[]',
    coding_style_guide TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Add FK constraint after both tables exist
ALTER TABLE projects ADD CONSTRAINT projects_ai_config_fkey FOREIGN KEY (ai_config_id) REFERENCES ai_configs(id);

CREATE TABLE IF NOT EXISTS modules (
    id UUID PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'DRAFT',
    current_agent VARCHAR(50),
    raw_requirement TEXT,
    description TEXT,
    project_id UUID NOT NULL REFERENCES projects(id),
    parsed_stories JSONB,
    req_file_path TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS agent_runs (
    id UUID PRIMARY KEY,
    agent_name VARCHAR(50) NOT NULL,
    run_order INTEGER NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    duration_seconds INTEGER,
    tokens_used INTEGER,
    error_message TEXT,
    retry_count INTEGER DEFAULT 0,
    module_id UUID NOT NULL REFERENCES modules(id),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS agent_reports (
    id UUID PRIMARY KEY,
    summary TEXT NOT NULL,
    confidence_score DECIMAL(3,2),
    confidence_reason TEXT,
    tokens_used INTEGER,
    duration_seconds INTEGER,
    run_id UUID NOT NULL REFERENCES agent_runs(id),
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE IF NOT EXISTS deliverables (
    id UUID PRIMARY KEY,
    type VARCHAR(50),
    name VARCHAR(255),
    file_path TEXT,
    description TEXT,
    lines INTEGER,
    report_id UUID REFERENCES agent_reports(id)
);

CREATE TABLE IF NOT EXISTS issues (
    id UUID PRIMARY KEY,
    severity VARCHAR(50),
    description TEXT,
    suggested_action TEXT,
    report_id UUID REFERENCES agent_reports(id)
);

CREATE TABLE IF NOT EXISTS next_steps (
    id UUID PRIMARY KEY,
    action TEXT,
    agent VARCHAR(50),
    priority VARCHAR(50),
    report_id UUID REFERENCES agent_reports(id)
);

CREATE TABLE IF NOT EXISTS owner_decisions (
    id UUID PRIMARY KEY,
    question TEXT,
    impact TEXT,
    report_id UUID REFERENCES agent_reports(id)
);

CREATE TABLE IF NOT EXISTS owner_decision_options (
    id UUID PRIMARY KEY,
    decision_id UUID REFERENCES owner_decisions(id),
    option_value TEXT
);

CREATE TABLE IF NOT EXISTS agent_sessions (
    id UUID PRIMARY KEY,
    run_id UUID UNIQUE REFERENCES agent_runs(id),
    structured_facts JSONB,
    summary TEXT,
    reasoning_summary TEXT,
    recent_raw_messages TEXT,
    status VARCHAR(50) DEFAULT 'ACTIVE',
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);
