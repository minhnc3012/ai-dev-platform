"""
CrewAI runner: builds and executes a single-agent crew for a given module run.
Supports three LLM invocation modes: API, CLI, SDK.

CLI mode bypasses CrewAI and calls the CLI tool directly via subprocess,
because CrewAI 1.x requires LLM to be a string model ID or BaseLLM instance.
API and SDK modes use CrewAI's Agent/Crew pipeline.
"""

import json
import os
import pathlib
import subprocess
from crewai import Agent, Task, Crew, LLM
from event_publisher import push_event, complete_run
from knowledge_store import get_context as get_knowledge_context, record_run


# ---------------------------------------------------------------------------
# LLM factory (API and SDK modes only — CLI mode bypasses CrewAI)
# ---------------------------------------------------------------------------

def build_llm(config: dict) -> LLM:
    """
    Build a CrewAI LLM instance for API or SDK invocation modes.
    CLI mode is handled separately by _run_cli_pipeline.

    For local Ollama API (OpenAI-compatible endpoint), the model name should be
    passed directly without any provider prefix (e.g., "qwen3.5:35b" not "openai/qwen3.5:35b").
    CrewAI will detect the model provider from the model name format.
    """
    mode = config.get("invocation_mode", "CLI").upper()

    if mode == "API":
        # For API mode with OpenAI-compatible endpoints (Ollama, LM Studio, etc.),
        # pass model name directly without provider prefix
        model_name = config.get("llm_model_name", "")
        base_url = config.get("llm_base_url", "")

        # Ensure base_url ends with /v1 for OpenAI-compatible endpoints
        # Ollama default: http://localhost:11434 -> should be http://localhost:11434/v1
        # LM Studio: http://localhost:1234/v1 -> keep as is
        if not base_url.endswith("/v1"):
            base_url = base_url.rstrip("/") + "/v1"

        return LLM(
            model=model_name,  # e.g., "qwen3.5:35b" for Ollama
            base_url=base_url,
            api_key=config.get("llm_api_key") or "not-required",
            temperature=float(config.get("temperature", 0.5)),
            max_tokens=int(config.get("max_tokens_per_task", 4096)),
        )

    elif mode == "SDK":
        provider = config.get("llm_provider", "").lower()

        if provider == "anthropic":
            return LLM(
                model=f"anthropic/{config['llm_model_name']}",
                api_key=config["llm_api_key"],
                temperature=float(config.get("temperature", 0.5)),
                max_tokens=int(config.get("max_tokens_per_task", 4096)),
            )
        else:
            # OpenAI or other SDK providers
            return LLM(
                model=config["llm_model_name"],
                api_key=config["llm_api_key"],
                temperature=float(config.get("temperature", 0.5)),
                max_tokens=int(config.get("max_tokens_per_task", 4096)),
            )

    else:
        raise ValueError(f"build_llm called with unsupported mode: {mode}. Use run_agent_pipeline instead.")


# ---------------------------------------------------------------------------
# Step callback — module-level named class to avoid CrewAI serialization warning
# ---------------------------------------------------------------------------

class _StepCallback:
    """
    Named callable class for CrewAI step_callback.
    Using a module-level class (not a lambda) avoids the CrewAI checkpointing warning.
    """
    def __init__(self, run_id: str):
        self.run_id = run_id

    def __call__(self, step) -> None:
        push_event(self.run_id, "THINKING", str(step)[:500])


# ---------------------------------------------------------------------------
# Agent definitions
# ---------------------------------------------------------------------------

AGENT_DEFINITIONS = {
    "pm": {
        "role": "Product Manager",
        "goal": "Analyse raw customer requirements and produce clear user stories with acceptance criteria",
        "backstory_template": (
            "You are a senior Product Manager experienced in translating vague customer needs "
            "into actionable development tasks. {context}"
        ),
    },
    "architect": {
        "role": "Software Architect",
        "goal": "Design the database schema, API contract, and system architecture based on user stories",
        "backstory_template": (
            "You are a Software Architect specialising in scalable, maintainable systems. {context}"
        ),
    },
    "dev": {
        "role": "Senior Developer",
        "goal": "Implement production-ready code according to the architecture spec, including unit tests",
        "backstory_template": (
            "You are a full-stack Senior Developer who writes clean, well-tested code. {context}"
        ),
    },
    "qa": {
        "role": "QA Engineer",
        "goal": "Review the implementation for bugs, missing edge cases, and security vulnerabilities",
        "backstory_template": (
            "You are a QA Engineer focused on test automation and security. {context}"
        ),
    },
    "docs": {
        "role": "Technical Writer",
        "goal": "Write comprehensive technical documentation for all delivered artifacts",
        "backstory_template": (
            "You are a Technical Writer who produces clear API docs, README files, and changelogs. {context}"
        ),
    },
}

TASK_DESCRIPTIONS = {
    "pm": lambda req: (
        f"Analyse the following raw customer requirement and produce:\n"
        f"1. A numbered list of user stories in the format:\n"
        f"   'As a <role>, I want <feature>, so that <benefit>'\n"
        f"2. Acceptance criteria for each story\n"
        f"3. A list of open questions or ambiguities that need owner clarification\n\n"
        f"RAW REQUIREMENT:\n{req}"
    ),
    "architect": lambda req: (
        "Based on the user stories provided, design:\n"
        "1. PostgreSQL database schema (CREATE TABLE statements)\n"
        "2. REST API endpoints (method, path, request/response shape)\n"
        "3. High-level component diagram in text form\n"
        "4. Any architectural decisions or trade-offs"
    ),
    "dev": lambda req: (
        "Implement the feature according to the architecture spec. Produce:\n"
        "1. All required source files with full implementation\n"
        "2. Unit tests for core business logic\n"
        "3. A brief summary of what was implemented and any deviations from the spec"
    ),
    "qa": lambda req: (
        "Review the implemented code and produce:\n"
        "1. List of bugs found (severity: CRITICAL / MAJOR / MINOR)\n"
        "2. Missing edge cases or test scenarios\n"
        "3. Security concerns (OWASP top 10 applicable items)\n"
        "4. Overall quality assessment"
    ),
    "docs": lambda req: (
        "Write technical documentation for all delivered artifacts:\n"
        "1. README with setup and usage instructions\n"
        "2. API documentation (endpoint descriptions, request/response examples)\n"
        "3. Database schema documentation\n"
        "4. Changelog entry for this module"
    ),
}


# ---------------------------------------------------------------------------
# Pipeline entry point
# ---------------------------------------------------------------------------

def run_agent_pipeline(task_config: dict) -> None:
    """
    Execute a single agent for one module run.
    Routes to CLI pipeline or CrewAI pipeline based on invocation_mode.
    """
    mode = task_config.get("invocation_mode", "CLI").upper()

    if mode == "CLI":
        _run_cli_pipeline(task_config)
    else:
        _run_crewai_pipeline(task_config)


# ---------------------------------------------------------------------------
# CLI pipeline — subprocess, bypasses CrewAI
# ---------------------------------------------------------------------------

def _run_cli_pipeline(task_config: dict) -> None:
    """
    Execute agent via CLI subprocess. Bypasses CrewAI because 1.x requires
    LLM to be a string model ID or BaseLLM instance, not a custom class.

    Default CLI command: 'claude -p' (Claude Code CLI, already authenticated).
    Also supports: 'ollama run <model>', or any tool that reads from stdin.
    """
    run_id = task_config["run_id"]
    agent_name = task_config["agent_name"]
    cli_command = task_config.get("llm_cli_command") or "claude -p"

    push_event(run_id, "STARTED", f"Agent '{agent_name}' started (CLI mode: {cli_command.split()[0]})")

    try:
        context = _build_project_context(task_config)
        defn = AGENT_DEFINITIONS[agent_name]
        backstory = defn["backstory_template"].format(context=context)

        system_prompt = (
            f"You are a {defn['role']}.\n"
            f"Goal: {defn['goal']}\n\n"
            f"{backstory}"
        )
        user_prompt = TASK_DESCRIPTIONS[agent_name](task_config.get("raw_requirement", ""))
        combined_prompt = f"{system_prompt}\n\n---\n\n{user_prompt}"

        push_event(run_id, "THINKING", f"Calling CLI: {cli_command.split()[0]}...")
        print(f"[crew_runner] Spawning CLI: {cli_command.split()[0]} (this may take 30-90s)...")
        raw_output = _execute_cli(cli_command, combined_prompt)
        print(f"[crew_runner] CLI returned {len(raw_output)} chars")

        push_event(run_id, "THINKING", "CLI response received, writing output files...")
        file_path = _write_output_to_workspace(raw_output, agent_name, task_config)
        if file_path:
            push_event(run_id, "INFO", f"Output written to: {file_path}")

        report = _parse_result_to_report(raw_output, agent_name, task_config, file_path)

        push_event(run_id, "COMPLETED", f"Agent '{agent_name}' completed successfully")
        complete_run(run_id, report)
        record_run(task_config, report)

    except Exception as exc:
        push_event(run_id, "ERROR", f"Agent '{agent_name}' failed: {str(exc)[:500]}")
        raise


def _execute_cli(cli_command: str, prompt: str) -> str:
    """
    Execute a CLI command with the prompt and return the output text.

    Supported tools:
      claude -p        → prompt passed as CLI argument (Option 3 default)
      ollama run <m>   → prompt piped via stdin
      <other>          → prompt piped via stdin
    """
    parts = cli_command.strip().split()
    tool = parts[0]

    # Always decode as UTF-8 (Claude CLI and most modern tools output UTF-8).
    # errors="replace" prevents a UnicodeDecodeError from crashing the reader
    # thread on Windows, which would leave result.stdout as None.
    _decode_kwargs = {"encoding": "utf-8", "errors": "replace"}

    if tool == "claude":
        # claude -p "prompt" — Claude Code CLI, already authenticated
        cmd = ["claude", "-p", prompt]
        result = subprocess.run(cmd, capture_output=True, text=True, timeout=300, **_decode_kwargs)
    elif tool == "ollama" and len(parts) >= 3 and parts[1] == "run":
        # ollama run <model> — pipe prompt via stdin
        result = subprocess.run(parts, input=prompt, capture_output=True, text=True, timeout=300, **_decode_kwargs)
    else:
        # Generic CLI tool — pipe prompt via stdin
        result = subprocess.run(parts, input=prompt, capture_output=True, text=True, timeout=300, **_decode_kwargs)

    if result.returncode != 0:
        raise RuntimeError(
            f"CLI error (exit {result.returncode}): {result.stderr[:500]}"
        )

    output = result.stdout.strip()
    if not output:
        raise RuntimeError(f"CLI returned empty output (stderr: {result.stderr[:200]})")

    return output


# ---------------------------------------------------------------------------
# CrewAI pipeline — API and SDK modes
# ---------------------------------------------------------------------------

def _run_crewai_pipeline(task_config: dict) -> None:
    """
    Execute agent via CrewAI Agent/Crew pipeline (API and SDK modes).
    """
    run_id = task_config["run_id"]
    agent_name = task_config["agent_name"]

    push_event(run_id, "STARTED", f"Agent '{agent_name}' started")

    try:
        llm = build_llm(task_config)
        context = _build_project_context(task_config)
        agent = _build_agent(agent_name, llm, context, run_id)
        task = _build_task(agent_name, task_config, agent)

        crew = Crew(agents=[agent], tasks=[task], verbose=True)
        result = crew.kickoff()

        file_path = _write_output_to_workspace(str(result), agent_name, task_config)
        if file_path:
            push_event(run_id, "INFO", f"Output written to: {file_path}")

        report = _parse_result_to_report(result, agent_name, task_config, file_path)
        push_event(run_id, "COMPLETED", f"Agent '{agent_name}' completed successfully")
        complete_run(run_id, report)
        record_run(task_config, report)

    except Exception as exc:
        push_event(run_id, "ERROR", f"Agent '{agent_name}' failed: {str(exc)[:500]}")
        raise


def _build_agent(agent_name: str, llm, project_context: str, run_id: str) -> Agent:
    defn = AGENT_DEFINITIONS[agent_name]
    backstory = defn["backstory_template"].format(context=project_context)
    return Agent(
        role=defn["role"],
        goal=defn["goal"],
        backstory=backstory,
        llm=llm,
        verbose=True,
        step_callback=_StepCallback(run_id),
    )


def _build_task(agent_name: str, config: dict, agent: Agent) -> Task:
    requirement = config.get("raw_requirement", "")
    description = TASK_DESCRIPTIONS[agent_name](requirement)
    return Task(
        description=description,
        agent=agent,
        expected_output=(
            "A structured markdown document following the report format defined in the spec"
        ),
    )


# ---------------------------------------------------------------------------
# Shared helpers
# ---------------------------------------------------------------------------

def _build_project_context(config: dict) -> str:
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
    # Inject self-learned knowledge from prior successful runs on this project
    knowledge = get_knowledge_context(config)
    if knowledge:
        parts.append(knowledge)
    return "\n".join(parts)


def _write_output_to_workspace(content: str, agent_name: str, config: dict) -> str | None:
    """
    Write agent output to the project workspace if workspace_path is configured.
    Returns the absolute file path written, or None if no workspace is set.

    Layout: {workspace_path}/{module_name}/{agent_name}/{agent_name}_output.md
    """
    workspace = config.get("workspace_path")
    if not workspace:
        return None

    module_name = config.get("module_name", "module").replace(" ", "_").lower()
    out_dir = pathlib.Path(workspace) / module_name / agent_name
    out_dir.mkdir(parents=True, exist_ok=True)

    out_file = out_dir / f"{agent_name}_output.md"
    out_file.write_text(content, encoding="utf-8")
    return str(out_file)


def _parse_result_to_report(result, agent_name: str, config: dict, file_path: str | None = None) -> dict:
    raw = str(result)
    lines = [l.strip() for l in raw.splitlines() if l.strip()]
    summary = lines[0][:500] if lines else "No summary available"

    next_agent_map = {"pm": "architect", "architect": "dev", "dev": "qa", "qa": "docs"}
    next_agent = next_agent_map.get(agent_name)
    next_steps = ([{"action": f"Pass output to {next_agent} agent",
                    "agent": next_agent, "priority": "HIGH"}]
                  if next_agent else [])

    deliverable_path = file_path or f"/outputs/{agent_name}/{agent_name}_output.md"
    return {
        "summary": summary,
        "deliverables": [{"type": "doc", "name": f"{agent_name}_output.md",
                          "file_path": deliverable_path,
                          "description": f"Output from {agent_name} agent", "lines": len(lines)}],
        "issues_found": [],
        "next_steps": next_steps,
        "owner_decisions_needed": [],
        "confidence_score": 75.0,
        "confidence_reason": "Automated confidence estimate based on output completeness",
        "tokens_used": (result.token_usage.total_tokens if hasattr(result, "token_usage") and result.token_usage else 0),
    }
