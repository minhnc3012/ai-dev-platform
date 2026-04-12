"""
Event publisher: sends real-time events and completion reports from Python agents
to the Spring Boot backend via its internal REST API.

This module now includes retry logic with state persistence to handle backend
unavailability gracefully. If the backend is temporarily down, completion reports
are retried with exponential backoff.
"""

import requests
import json
import os
from datetime import datetime
from dotenv import load_dotenv

from agent_state import (
    AgentState,
    AgentRunState,
    BackendCallbackHandler,
)

load_dotenv()

BACKEND_URL = os.getenv("BACKEND_URL", "http://localhost:8080/api/internal/agent")
INTERNAL_API_KEY = os.getenv("AGENT_API_KEY", "dev-secret")

_HEADERS = {
    "Content-Type": "application/json",
    "X-Agent-Key": INTERNAL_API_KEY,
}

# Global callback handler for retry logic
_callback_handler = None


def _get_callback_handler() -> BackendCallbackHandler:
    """Get or create the global callback handler."""
    global _callback_handler
    if _callback_handler is None:
        _callback_handler = BackendCallbackHandler(
            backend_url=BACKEND_URL
        )
    return _callback_handler


def _get_state_tracker() -> AgentState:
    """Get the global state tracker."""
    return AgentState()


def push_event(run_id: str, event_type: str, message: str, payload: dict = None) -> bool:
    """
    Send a real-time event to the Spring Boot backend with retry logic.
    The backend persists the event and broadcasts it to the owner via SSE.

    Args:
        run_id:     UUID string of the AgentRun record.
        event_type: One of STARTED | THINKING | TOOL_CALL | TOOL_RESULT |
                    INFO | WARNING | ERROR | COMPLETED
        message:    Human-readable description of what is happening.
        payload:    Optional dict with additional structured data.

    Returns:
        True if event was delivered (with or without retry), False if failed
    """
    data = {
        "runId": run_id,
        "eventType": event_type,
        "message": message,
        "payload": payload or {},
        "timestamp": datetime.utcnow().isoformat(),
    }

    try:
        handler = _get_callback_handler()
        return handler.push_event(run_id, event_type, message, payload)
    except Exception as exc:
        # Do not raise; event delivery failure must not abort the agent run
        print(f"[event_publisher] WARNING: Failed to push event '{event_type}': {exc}")
        return False


def complete_run(run_id: str, report: dict) -> bool:
    """
    Notify the backend that this agent run has finished and submit the structured report.
    Implements retry logic with state persistence to handle backend unavailability.

    The backend will persist the report, then either await approval or trigger the next agent.

    Args:
        run_id: UUID string of the AgentRun record.
        report: Structured report dict matching the standard JSON contract (see spec section 13).

    Returns:
        True if completion was successfully reported, False if max retries exceeded
    """
    data = {
        "runId": run_id,
        "report": report,
        "tokensUsed": report.get("tokens_used", 0),
    }

    try:
        handler = _get_callback_handler()
        return handler.complete_run(run_id, data["report"])
    except Exception as exc:
        print(f"[event_publisher] ERROR: Failed to complete run: {exc}")
        return False


def fetch_interrupted_runs() -> list:
    """
    Fetch all agent runs that were RUNNING when the service last stopped.
    Returns a list of full task config dicts ready for run_agent_pipeline().
    Returns empty list if the backend is unreachable or returns an error.
    """
    try:
        response = requests.get(
            f"{BACKEND_URL}/recovery/interrupted",
            headers=_HEADERS,
            timeout=10,
        )
        if response.status_code == 200:
            return response.json()
        print(f"[event_publisher] Recovery endpoint returned {response.status_code}")
        return []
    except Exception as exc:
        print(f"[event_publisher] WARNING: Could not fetch interrupted runs: {exc}")
        return []
