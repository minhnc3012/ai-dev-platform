"""
Report tools for agents: helpers for building structured report dicts
that conform to the standard JSON contract (spec section 13).
"""

from typing import Optional


def build_report(
    summary: str,
    deliverables: list[dict] | None = None,
    issues_found: list[dict] | None = None,
    next_steps: list[dict] | None = None,
    owner_decisions_needed: list[dict] | None = None,
    confidence_score: float = 75.0,
    confidence_reason: str = "",
    tokens_used: int = 0,
) -> dict:
    """
    Build a structured report dict matching the standard JSON contract.

    All string values should be in English unless the project's output_language is 'vi'.
    Field names must always remain in English regardless of output_language.

    Returns:
        Report dict ready to pass to event_publisher.complete_run().
    """
    return {
        "summary": summary,
        "deliverables": deliverables or [],
        "issues_found": issues_found or [],
        "next_steps": next_steps or [],
        "owner_decisions_needed": owner_decisions_needed or [],
        "confidence_score": round(confidence_score, 2),
        "confidence_reason": confidence_reason,
        "tokens_used": tokens_used,
    }


def make_deliverable(
    type: str,
    name: str,
    file_path: str,
    description: str = "",
    lines: int = 0,
) -> dict:
    """
    Build a single deliverable entry.

    Args:
        type:        "code" | "doc" | "test" | "schema"
        name:        File or artifact name.
        file_path:   Path where the artifact is stored.
        description: Human-readable description.
        lines:       Approximate line count.
    """
    return {
        "type": type,
        "name": name,
        "file_path": file_path,
        "description": description,
        "lines": lines,
    }


def make_issue(severity: str, description: str, suggested_action: str = "") -> dict:
    """
    Build a single issue entry.

    Args:
        severity:         "BLOCKING" | "NON_BLOCKING"
        description:      Description of the issue.
        suggested_action: Recommended resolution.
    """
    return {
        "severity": severity,
        "description": description,
        "suggested_action": suggested_action,
    }


def make_next_step(action: str, agent: str, priority: str = "MEDIUM") -> dict:
    """
    Build a single next-step entry.

    Args:
        action:   Description of the recommended action.
        agent:    Target agent name ("pm" | "architect" | "dev" | "qa" | "docs").
        priority: "HIGH" | "MEDIUM" | "LOW"
    """
    return {
        "action": action,
        "agent": agent,
        "priority": priority,
    }


def make_owner_decision(question: str, options: list[str], impact: str = "") -> dict:
    """
    Build a single owner-decision entry.

    Args:
        question: The question the owner must answer.
        options:  List of possible answers.
        impact:   Description of how this decision affects the project.
    """
    return {
        "question": question,
        "options": options,
        "impact": impact,
    }
