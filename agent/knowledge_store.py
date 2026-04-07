"""
Knowledge store: persists and retrieves learnings from completed agent runs.

Each project accumulates a knowledge base in JSON format under:
    {KNOWLEDGE_DIR}/{project_module_key}.json

On each completed run the agent extracts key patterns (tech stack used,
common issues, architectural decisions) and appends them to the store.
On subsequent runs the relevant knowledge is injected into the agent context,
helping agents analyse faster and produce more consistent output.
"""

import json
import os
import pathlib
from datetime import datetime

KNOWLEDGE_DIR = os.getenv("KNOWLEDGE_DIR", os.path.join(os.path.dirname(__file__), ".knowledge"))


def _store_path(project_key: str) -> pathlib.Path:
    """Return the JSON file path for a given project key."""
    directory = pathlib.Path(KNOWLEDGE_DIR)
    directory.mkdir(parents=True, exist_ok=True)
    safe_key = "".join(c if c.isalnum() or c in "-_" else "_" for c in project_key)
    return directory / f"{safe_key}.json"


def _load(project_key: str) -> dict:
    path = _store_path(project_key)
    if path.exists():
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except (json.JSONDecodeError, OSError):
            return {}
    return {}


def _save(project_key: str, data: dict) -> None:
    path = _store_path(project_key)
    try:
        path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding="utf-8")
    except OSError as exc:
        print(f"[knowledge_store] WARNING: Could not save knowledge: {exc}")


def record_run(task_config: dict, report: dict) -> None:
    """
    After a successful agent run, extract key learnings and append them to the store.
    Called by crew_runner after complete_run() succeeds.

    Learnings stored per agent:
      - tech_stack observed
      - confidence_score achieved
      - common issues identified (severity + description)
      - summary snippet (first 300 chars) for quick context
      - architectural/coding patterns noted in deliverables
    """
    module_id = task_config.get("module_id", "unknown")
    agent_name = task_config.get("agent_name", "unknown")
    project_key = f"project_{module_id[:8]}"  # short key from module UUID prefix

    data = _load(project_key)

    if "runs" not in data:
        data["runs"] = []
    if "patterns" not in data:
        data["patterns"] = {}

    # Append a compact run record
    run_record = {
        "ts": datetime.utcnow().isoformat(),
        "agent": agent_name,
        "module_id": module_id,
        "confidence": report.get("confidence_score", 0),
        "summary_snippet": (report.get("summary") or "")[:300],
        "tech_stack": task_config.get("tech_stack", []),
        "issues": [
            {"severity": i.get("severity"), "desc": i.get("description", "")[:120]}
            for i in (report.get("issues_found") or [])[:5]
        ],
    }
    data["runs"].append(run_record)

    # Keep only the 50 most recent runs per store to avoid unbounded growth
    if len(data["runs"]) > 50:
        data["runs"] = data["runs"][-50:]

    # Accumulate per-agent patterns (deduplicated snippets)
    agent_patterns = data["patterns"].setdefault(agent_name, [])
    snippet = run_record["summary_snippet"]
    if snippet and snippet not in agent_patterns:
        agent_patterns.append(snippet)
    if len(agent_patterns) > 10:
        agent_patterns[:] = agent_patterns[-10:]

    _save(project_key, data)
    print(f"[knowledge_store] Recorded run for module={module_id}, agent={agent_name}")


def get_context(task_config: dict) -> str:
    """
    Return a knowledge context string for injection into the agent's backstory.
    Includes patterns from prior successful runs of the same agent type.

    Returns an empty string if no prior knowledge exists.
    """
    module_id = task_config.get("module_id", "unknown")
    agent_name = task_config.get("agent_name", "unknown")
    project_key = f"project_{module_id[:8]}"

    data = _load(project_key)
    if not data:
        return ""

    patterns = data.get("patterns", {}).get(agent_name, [])
    if not patterns:
        return ""

    lines = [
        f"\n--- Knowledge from previous {agent_name} runs on this project ---",
    ]
    for i, p in enumerate(patterns[-3:], 1):  # last 3 patterns are most relevant
        lines.append(f"{i}. {p}")
    lines.append("--- End of knowledge context ---")

    return "\n".join(lines)
