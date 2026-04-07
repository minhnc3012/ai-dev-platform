"""
Event publisher: sends real-time events and completion reports from Python agents
to the Spring Boot backend via its internal REST API.
"""

import requests
import json
import os
from datetime import datetime
from dotenv import load_dotenv

load_dotenv()

BACKEND_URL = os.getenv("BACKEND_URL", "http://localhost:8080/api/internal/agent")
INTERNAL_API_KEY = os.getenv("AGENT_API_KEY", "dev-secret")

_HEADERS = {
    "Content-Type": "application/json",
    "X-Agent-Key": INTERNAL_API_KEY,
}


def push_event(run_id: str, event_type: str, message: str, payload: dict = None) -> None:
    """
    Send a real-time event to the Spring Boot backend.
    The backend persists the event and broadcasts it to the owner via SSE.

    Args:
        run_id:     UUID string of the AgentRun record.
        event_type: One of STARTED | THINKING | TOOL_CALL | TOOL_RESULT |
                    INFO | WARNING | ERROR | COMPLETED
        message:    Human-readable description of what is happening.
        payload:    Optional dict with additional structured data.
    """
    data = {
        "runId": run_id,
        "eventType": event_type,
        "message": message,
        "payload": payload or {},
        "timestamp": datetime.utcnow().isoformat(),
    }
    try:
        requests.post(
            f"{BACKEND_URL}/event",
            json=data,
            headers=_HEADERS,
            timeout=5,
        )
    except Exception as exc:
        # Do not raise; event delivery failure must not abort the agent run
        print(f"[event_publisher] WARNING: Failed to push event '{event_type}': {exc}")


def complete_run(run_id: str, report: dict) -> None:
    """
    Notify the backend that this agent run has finished and submit the structured report.
    The backend will persist the report, then either await approval or trigger the next agent.

    Args:
        run_id: UUID string of the AgentRun record.
        report: Structured report dict matching the standard JSON contract (see spec section 13).
    """
    data = {
        "runId": run_id,
        "report": report,
        "tokensUsed": report.get("tokens_used", 0),
    }
    requests.post(
        f"{BACKEND_URL}/complete",
        json=data,
        headers=_HEADERS,
        timeout=30,
    )


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
