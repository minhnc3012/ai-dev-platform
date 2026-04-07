"""
File tools for agents: read and write artifacts to the output directory.
"""

import os
from pathlib import Path

OUTPUT_BASE = os.getenv("AGENT_OUTPUT_DIR", "/tmp/agent_outputs")


def write_artifact(module_id: str, agent_name: str, filename: str, content: str) -> str:
    """
    Write an artifact file produced by an agent.

    Args:
        module_id:  UUID string of the module.
        agent_name: Name of the producing agent.
        filename:   File name for the artifact.
        content:    File content to write.

    Returns:
        Absolute path to the written file.
    """
    output_dir = Path(OUTPUT_BASE) / module_id / agent_name
    output_dir.mkdir(parents=True, exist_ok=True)
    file_path = output_dir / filename
    file_path.write_text(content, encoding="utf-8")
    return str(file_path)


def read_artifact(file_path: str) -> str:
    """
    Read an artifact file from disk.

    Args:
        file_path: Absolute path to the file.

    Returns:
        File content as a string.

    Raises:
        FileNotFoundError: if the file does not exist.
    """
    path = Path(file_path)
    if not path.exists():
        raise FileNotFoundError(f"Artifact not found: {file_path}")
    return path.read_text(encoding="utf-8")


def list_artifacts(module_id: str, agent_name: str = None) -> list[str]:
    """
    List artifact file paths for a module, optionally filtered by agent.

    Args:
        module_id:  UUID string of the module.
        agent_name: Optional agent name filter. If None, returns all agents' artifacts.

    Returns:
        List of absolute file paths.
    """
    base = Path(OUTPUT_BASE) / module_id
    if agent_name:
        base = base / agent_name
    if not base.exists():
        return []
    return [str(p) for p in base.rglob("*") if p.is_file()]
