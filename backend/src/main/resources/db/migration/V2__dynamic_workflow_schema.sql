-- V2: Dynamic agent templates and workflow definitions

CREATE TABLE agent_templates (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID REFERENCES projects(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    agent_key VARCHAR(100) NOT NULL,
    role TEXT NOT NULL,
    goal TEXT NOT NULL,
    backstory_template TEXT,
    task_description_template TEXT,
    llm_config JSONB DEFAULT '{}',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE workflow_definitions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    project_id UUID REFERENCES projects(id) ON DELETE CASCADE,
    name VARCHAR(200) NOT NULL,
    description TEXT,
    is_template BOOLEAN DEFAULT FALSE,
    default_pause_for_review BOOLEAN DEFAULT TRUE,
    stages JSONB NOT NULL DEFAULT '[]',
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

CREATE TABLE agent_chat_messages (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id UUID NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    revision_number INTEGER DEFAULT 0,
    created_at TIMESTAMP DEFAULT NOW(),
    updated_at TIMESTAMP DEFAULT NOW()
);

ALTER TABLE modules ADD COLUMN IF NOT EXISTS workflow_id UUID REFERENCES workflow_definitions(id);

ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS stage_id VARCHAR(150);
ALTER TABLE agent_runs ADD COLUMN IF NOT EXISTS agent_template_id UUID REFERENCES agent_templates(id);
UPDATE agent_runs SET stage_id = agent_name WHERE stage_id IS NULL;
ALTER TABLE agent_runs ALTER COLUMN stage_id SET NOT NULL;
ALTER TABLE agent_runs DROP CONSTRAINT IF EXISTS agent_runs_module_id_agent_name_key;
ALTER TABLE agent_runs ADD CONSTRAINT agent_runs_module_id_stage_id_key UNIQUE (module_id, stage_id);

CREATE INDEX idx_agent_templates_project_id ON agent_templates(project_id);
CREATE INDEX idx_workflow_definitions_project_id ON workflow_definitions(project_id);
CREATE INDEX idx_agent_chat_messages_run_id ON agent_chat_messages(run_id);
CREATE INDEX idx_agent_runs_stage_id ON agent_runs(stage_id);
CREATE INDEX idx_modules_workflow_id ON modules(workflow_id);
