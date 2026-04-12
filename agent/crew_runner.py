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
import re
import subprocess
import threading
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
# Step callback — module-level function with thread-local run_id
# ---------------------------------------------------------------------------
# Pydantic/CrewAI cannot serialize a stateful callable instance (even a named
# class). A plain module-level function is serializable; run_id is threaded
# through a thread-local so parallel agents don't interfere with each other.

_step_callback_local = threading.local()


def _step_callback(step) -> None:
    run_id = getattr(_step_callback_local, "run_id", "unknown")
    push_event(run_id, "THINKING", str(step)[:500])


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
        "goal": "Design ONLY what the requirement asks for: file structure, data structures, and component interactions — no databases or APIs unless explicitly required",
        "backstory_template": (
            "You are a Software Architect who designs minimal, requirement-driven solutions. {context}"
        ),
    },
    "dev": {
        "role": "Senior Developer",
        "goal": "Write and output every source code file needed to satisfy the requirement — actual runnable code, not documentation",
        "backstory_template": (
            "You are a Senior Developer. Your ONLY output is working source code files. "
            "You do NOT write explanations, markdown prose, or documentation — you write code. {context}"
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

def _task_pm(req: str, tree: list[str], _contents: dict) -> str:
    return (
        f"Analyse the following raw customer requirement and produce:\n"
        f"1. A numbered list of user stories in the format:\n"
        f"   'As a <role>, I want <feature>, so that <benefit>'\n"
        f"2. Acceptance criteria for each story\n"
        f"3. A list of open questions or ambiguities that need owner clarification\n\n"
        f"RAW REQUIREMENT:\n{req}"
    )


def _task_architect(req: str, tree: list[str], contents: dict) -> str:
    base = (
        f"Design ONLY what the requirement asks for. "
        f"Do not introduce databases, APIs, or infrastructure unless explicitly required.\n\n"
        f"Produce:\n"
        f"1. File/module structure — list every file that must be created or modified\n"
        f"2. Data structures or classes needed\n"
        f"3. Component interactions or call flow\n"
        f"4. Any architectural decisions relevant to THIS requirement\n\n"
        f"RAW REQUIREMENT:\n{req}"
    )
    if tree:
        file_list = "\n".join(f"  {f}" for f in tree)
        base += (
            f"\n\nEXISTING PROJECT (review before designing):\n{file_list}\n"
            f"Your design must be consistent with the existing structure above. "
            f"Identify which existing files to modify and which new files to add."
        )
    return base


def _task_dev(req: str, tree: list[str], _contents: dict) -> str:
    output_format = (
        f"OUTPUT FORMAT — wrap every file in this XML tag (raw source, no markdown fences):\n"
        f"<file path=\"relative/path/FileName.ext\">\n"
        f"[complete file content]\n"
        f"</file>\n\n"
        f"Rules:\n"
        f"- Output ONLY <file> blocks — no prose or explanation outside the tags\n"
        f"- Paths are relative to the workspace root shown in context\n"
        f"- Use the correct extension (.java, .py, .ts …) for the tech stack\n"
        f"- Include unit tests as a separate <file> block when applicable\n"
        f"- Do NOT add databases, REST endpoints, or frameworks unless the requirement asks\n\n"
    )

    if tree:
        file_list = "\n".join(f"  {f}" for f in tree)
        project_instruction = (
            f"EXISTING PROJECT — implement ON TOP of the project already in the workspace.\n"
            f"Current files:\n{file_list}\n\n"
            f"- Preserve the existing structure and coding conventions\n"
            f"- Only output files that are new or changed\n"
            f"- If a file already exists and needs modification, output the FULL updated version\n\n"
        )
    else:
        project_instruction = (
            f"EMPTY WORKSPACE — create the project from scratch.\n"
            f"- Generate all necessary scaffold files (e.g. pom.xml, package.json, build.gradle, "
            f".gitignore) appropriate for the tech stack\n"
            f"- Then implement the requirement\n\n"
        )

    return (
        f"Implement exactly what the requirement asks for — nothing more, nothing less.\n\n"
        f"{project_instruction}"
        f"{output_format}"
        f"RAW REQUIREMENT:\n{req}"
    )


def _task_qa(req: str, tree: list[str], contents: dict) -> str:
    base = (
        f"Review the implementation against the requirement. Produce:\n"
        f"1. Does the implementation satisfy the requirement? (YES / PARTIAL / NO — explain)\n"
        f"2. Bugs found (severity: CRITICAL / MAJOR / MINOR)\n"
        f"3. Missing edge cases or test scenarios\n"
        f"4. Security concerns if applicable\n"
        f"5. Overall quality assessment\n\n"
        f"RAW REQUIREMENT:\n{req}"
    )
    if contents:
        src = {p: c for p, c in contents.items()
               if pathlib.Path(p).suffix.lower() not in {'.md', '.txt', '.json', '.yaml', '.yml'}}
        if src:
            files_block = "\n\n".join(f"### {p}\n```\n{c}\n```" for p, c in src.items())
            base += f"\n\nSOURCE FILES TO REVIEW:\n{files_block}"
    return base


def _task_docs(req: str, tree: list[str], contents: dict) -> str:
    base = (
        f"Write documentation for what was actually built.\n"
        f"Produce:\n"
        f"1. README — purpose, setup, and usage instructions\n"
        f"2. Description of every public function/class with parameters and return values\n"
        f"3. Example input/output\n"
        f"4. Changelog entry for this change\n\n"
        f"RAW REQUIREMENT:\n{req}"
    )
    if contents:
        src = {p: c for p, c in contents.items()
               if pathlib.Path(p).suffix.lower() not in {'.md', '.txt'}}
        if src:
            files_block = "\n\n".join(f"### {p}\n```\n{c}\n```" for p, c in src.items())
            base += f"\n\nSOURCE FILES TO DOCUMENT:\n{files_block}"
    return base


TASK_DESCRIPTIONS = {
    "pm":        _task_pm,
    "architect": _task_architect,
    "dev":       _task_dev,
    "qa":        _task_qa,
    "docs":      _task_docs,
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
        tree, file_contents = _scan_workspace(task_config)
        if tree:
            push_event(run_id, "INFO", f"Workspace scanned: {len(tree)} file(s) found")
        else:
            push_event(run_id, "INFO", "Workspace is empty — will create new project")

        context = _build_project_context(task_config, tree, file_contents)
        defn = AGENT_DEFINITIONS[agent_name]
        backstory = defn["backstory_template"].format(context=context)

        system_prompt = (
            f"You are a {defn['role']}.\n"
            f"Goal: {defn['goal']}\n\n"
            f"{backstory}"
        )
        user_prompt = TASK_DESCRIPTIONS[agent_name](
            task_config.get("raw_requirement", ""), tree, file_contents
        )
        combined_prompt = f"{system_prompt}\n\n---\n\n{user_prompt}"

        push_event(run_id, "THINKING", f"Calling CLI: {cli_command.split()[0]}...")
        print(f"[crew_runner] Spawning CLI: {cli_command.split()[0]} (this may take 30-90s)...")
        raw_output = _execute_cli(cli_command, combined_prompt)
        print(f"[crew_runner] CLI returned {len(raw_output)} chars")

        push_event(run_id, "THINKING", "CLI response received, writing output files...")
        file_path = _write_output_to_workspace(raw_output, agent_name, task_config)
        if file_path:
            push_event(run_id, "INFO", f"Raw output written to: {file_path}")

        source_files: list[str] = []
        if agent_name == "dev":
            source_files = _write_dev_source_files(raw_output, task_config)
            if source_files:
                for sf in source_files:
                    push_event(run_id, "INFO", f"Source file created: {sf}")
            else:
                push_event(run_id, "WARNING",
                           "Dev agent produced no <file path=...> blocks — check raw output")

        report = _parse_result_to_report(raw_output, agent_name, task_config, file_path, source_files)

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
        # Pass all flags from cli_command (e.g., --settings, --model) then append prompt.
        # If user already included -p in parts, just append prompt; otherwise add -p first.
        if "-p" in parts:
            cmd = parts + [prompt]
        else:
            cmd = parts + ["-p", prompt]
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
        tree, file_contents = _scan_workspace(task_config)
        if tree:
            push_event(run_id, "INFO", f"Workspace scanned: {len(tree)} file(s) found")

        llm = build_llm(task_config)
        context = _build_project_context(task_config, tree, file_contents)
        agent = _build_agent(agent_name, llm, context, run_id)
        task = _build_task(agent_name, task_config, agent, tree, file_contents)

        crew = Crew(agents=[agent], tasks=[task], verbose=True)
        result = crew.kickoff()

        raw_text = str(result)
        file_path = _write_output_to_workspace(raw_text, agent_name, task_config)
        if file_path:
            push_event(run_id, "INFO", f"Raw output written to: {file_path}")

        source_files: list[str] = []
        if agent_name == "dev":
            source_files = _write_dev_source_files(raw_text, task_config)
            if source_files:
                for sf in source_files:
                    push_event(run_id, "INFO", f"Source file created: {sf}")
            else:
                push_event(run_id, "WARNING",
                           "Dev agent produced no <file path=...> blocks — check raw output")

        report = _parse_result_to_report(result, agent_name, task_config, file_path, source_files)
        push_event(run_id, "COMPLETED", f"Agent '{agent_name}' completed successfully")
        complete_run(run_id, report)
        record_run(task_config, report)

    except Exception as exc:
        push_event(run_id, "ERROR", f"Agent '{agent_name}' failed: {str(exc)[:500]}")
        raise


def _build_agent(agent_name: str, llm, project_context: str, run_id: str) -> Agent:
    defn = AGENT_DEFINITIONS[agent_name]
    backstory = defn["backstory_template"].format(context=project_context)
    _step_callback_local.run_id = run_id
    return Agent(
        role=defn["role"],
        goal=defn["goal"],
        backstory=backstory,
        llm=llm,
        verbose=True,
        step_callback=_step_callback,
    )


_EXPECTED_OUTPUT = {
    "dev": (
        "One or more <file path=\"...\"> blocks containing raw source code. "
        "No markdown prose outside the file tags."
    ),
}

def _build_task(
    agent_name: str,
    config: dict,
    agent: Agent,
    tree: list[str] | None = None,
    file_contents: dict[str, str] | None = None,
) -> Task:
    if tree is None or file_contents is None:
        tree, file_contents = _scan_workspace(config)
    requirement = config.get("raw_requirement", "")
    description = TASK_DESCRIPTIONS[agent_name](requirement, tree, file_contents)
    expected = _EXPECTED_OUTPUT.get(
        agent_name,
        "A structured markdown document with analysis, decisions, and findings"
    )
    return Task(description=description, agent=agent, expected_output=expected)


# ---------------------------------------------------------------------------
# Shared helpers
# ---------------------------------------------------------------------------

# ---------------------------------------------------------------------------
# Workspace scanning
# ---------------------------------------------------------------------------

_SKIP_DIRS = {
    '.git', 'node_modules', '__pycache__', '.idea', 'target', 'build',
    'dist', '.gradle', '.mvn', 'venv', '.venv', '.env', '.next', 'coverage',
}
_TEXT_EXTENSIONS = {
    '.java', '.py', '.ts', '.tsx', '.js', '.jsx', '.kt', '.go', '.rs',
    '.c', '.cpp', '.h', '.cs', '.rb', '.php', '.swift',
    '.json', '.xml', '.yaml', '.yml', '.toml', '.properties', '.gradle',
    '.sql', '.sh', '.bat', '.md', '.txt', '.html', '.css', '.scss',
}
_MAX_FILE_BYTES = 8_000   # truncate files larger than this
_MAX_FILES_IN_CONTEXT = 40


def _scan_workspace(config: dict) -> tuple[list[str], dict[str, str]]:
    """
    Scan the project workspace and return:
      tree     — sorted list of relative file paths (all files)
      contents — {rel_path: text} for up to _MAX_FILES_IN_CONTEXT text files

    Root is workspace_path (the project root as set in Project Settings).
    Agent-output files inside a module subdirectory are excluded so agents
    only see real project artefacts.
    """
    workspace = config.get("workspace_path", "").strip()
    if not workspace:
        return [], {}

    root = pathlib.Path(workspace)
    if not root.exists():
        return [], {}

    # The module output folder is {workspace}/{module_name}/ — exclude it so
    # agents don't confuse previous output files with actual source code.
    module_subdir = config.get("module_name", "").replace(" ", "_").lower()

    tree: list[str] = []
    contents: dict[str, str] = {}

    for path in sorted(root.rglob("*")):
        if not path.is_file():
            continue
        parts = path.relative_to(root).parts
        # Skip unwanted directories
        if any(p in _SKIP_DIRS for p in parts):
            continue
        # Skip the agent-output subfolder
        if module_subdir and parts[0] == module_subdir:
            continue
        # Skip compiled artefacts
        if path.suffix in {'.class', '.pyc', '.pyo', '.o', '.obj', '.jar', '.war'}:
            continue

        rel = "/".join(parts)
        tree.append(rel)

        if path.suffix.lower() in _TEXT_EXTENSIONS and len(contents) < _MAX_FILES_IN_CONTEXT:
            try:
                raw = path.read_bytes()
                text = raw[:_MAX_FILE_BYTES].decode("utf-8", errors="replace")
                if len(raw) > _MAX_FILE_BYTES:
                    text += f"\n... [truncated — {len(raw):,} bytes total]"
                contents[rel] = text
            except Exception:
                pass

    return tree, contents


def _build_project_context(config: dict, tree: list[str], contents: dict[str, str]) -> str:
    parts = [
        f"Tech stack: {', '.join(config.get('tech_stack', []))}",
        f"Output language for reports: {config.get('output_language', 'en')}",
    ]

    # ── Workspace state ───────────────────────────────────────────────────────
    workspace = config.get("workspace_path", "")
    if workspace:
        parts.append(f"\nWorkspace root: {workspace}")

    if tree:
        parts.append("\n## Existing project files:")
        parts.append("\n".join(f"  {f}" for f in tree))
        if contents:
            parts.append("\n## File contents:")
            for rel_path, text in contents.items():
                parts.append(f"\n### {rel_path}\n```\n{text}\n```")
    else:
        parts.append("\n## Workspace: empty — no existing project files found.")

    # ── Previous agent outputs ────────────────────────────────────────────────
    if config.get("coding_style_guide"):
        parts.append(f"\nCoding style guide:\n{config['coding_style_guide']}")
    if config.get("previous_outputs"):
        parts.append(
            f"\nOutputs from previous agents:\n"
            f"{json.dumps(config['previous_outputs'], ensure_ascii=False, indent=2)}"
        )
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


def _write_dev_source_files(raw_output: str, config: dict) -> list[str]:
    """
    Parse every <file path="...">...</file> block in the dev agent's output
    and write each one as a real source file under the workspace root.

    Paths inside the tag are relative to workspace_path (the project root),
    so the LLM can write "src/main/java/Main.java" or "package.json" directly
    into the right location of an existing or new project.

    Returns the list of absolute paths that were written.
    """
    workspace = config.get("workspace_path", "").strip()
    if not workspace:
        return []

    base_dir = pathlib.Path(workspace)
    base_dir.mkdir(parents=True, exist_ok=True)

    pattern = re.compile(
        r'<file\s+path=["\']([^"\']+)["\']>(.*?)</file>',
        re.DOTALL | re.IGNORECASE,
    )

    written: list[str] = []
    for match in pattern.finditer(raw_output):
        rel_path = match.group(1).strip()
        content = match.group(2).lstrip("\n").rstrip() + "\n"

        target = base_dir / rel_path
        # Safety: stay inside workspace_path, reject path traversal
        try:
            target.resolve().relative_to(base_dir.resolve())
        except ValueError:
            print(f"[crew_runner] SKIP unsafe path: {rel_path}")
            continue

        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
        written.append(str(target))
        print(f"[crew_runner] Source file written: {target}")

    return written


def _parse_result_to_report(
    result,
    agent_name: str,
    config: dict,
    file_path: str | None = None,
    source_files: list[str] | None = None,
) -> dict:
    raw = str(result)
    lines = [l.strip() for l in raw.splitlines() if l.strip()]
    summary = lines[0][:500] if lines else "No summary available"

    next_agent_map = {"pm": "architect", "architect": "dev", "dev": "qa", "qa": "docs"}
    next_agent = next_agent_map.get(agent_name)
    next_steps = ([{"action": f"Pass output to {next_agent} agent",
                    "agent": next_agent, "priority": "HIGH"}]
                  if next_agent else [])

    # For the dev agent: list each generated source file as its own deliverable.
    # For other agents: list the single markdown output file.
    if agent_name == "dev" and source_files:
        deliverables = [
            {
                "type": "code",
                "name": pathlib.Path(p).name,
                "file_path": p,
                "description": f"Source file generated by dev agent",
                "lines": len(pathlib.Path(p).read_text(encoding="utf-8").splitlines()),
            }
            for p in source_files
        ]
        if not deliverables:
            # Fallback: no <file> blocks found — report the raw output file
            deliverables = [{"type": "doc", "name": "dev_output.md",
                             "file_path": file_path or "/outputs/dev/dev_output.md",
                             "description": "Dev agent raw output (no source files parsed)", "lines": len(lines)}]
        summary = f"Dev agent created {len(source_files)} source file(s): " + \
                  ", ".join(pathlib.Path(p).name for p in source_files)
    else:
        deliverable_path = file_path or f"/outputs/{agent_name}/{agent_name}_output.md"
        deliverables = [{"type": "doc", "name": f"{agent_name}_output.md",
                         "file_path": deliverable_path,
                         "description": f"Output from {agent_name} agent", "lines": len(lines)}]

    return {
        "summary": summary,
        "deliverables": deliverables,
        "issues_found": [],
        "next_steps": next_steps,
        "owner_decisions_needed": [],
        "confidence_score": 75.0,
        "confidence_reason": "Automated confidence estimate based on output completeness",
        "tokens_used": (result.token_usage.total_tokens if hasattr(result, "token_usage") and result.token_usage else 0),
    }
