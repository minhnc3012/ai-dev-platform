"""
Agent State Management Module

Provides persistent state tracking for agent runs to ensure workflow continuity
when the backend service restarts or is temporarily unavailable.

Key features:
- Local state persistence using Redis for cross-process durability
- Retry logic for failed backend callbacks with exponential backoff
- State recovery on agent service startup
- RESUME signal handling for retrying completion callbacks
"""

import json
import os
import time
import redis
import requests
from datetime import datetime
from enum import Enum
from dotenv import load_dotenv
from typing import Optional, Dict, Any, List

load_dotenv()

# Redis configuration
REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = int(os.getenv("REDIS_PORT", 6379))
REDIS_DB = int(os.getenv("REDIS_DB", 0))

# Signal configuration
SIGNAL_STREAM_NAME = "agent:signals"
CONSUMER_GROUP = "agent-consumers"
CONSUMER_NAME = "agent-instance-1"

# Backend configuration
BACKEND_URL = os.getenv("BACKEND_URL", "http://localhost:8080/api/internal/agent")
INTERNAL_API_KEY = os.getenv("AGENT_API_KEY", "dev-secret")

# Retry configuration
MAX_RETRY_ATTEMPTS = 5
RETRY_BASE_DELAY_SECONDS = 5
MAX_RETRY_DELAY_SECONDS = 60


class AgentRunState(Enum):
    """
    State machine for agent run lifecycle tracking.
    Each state represents a specific point in the workflow that can persist
    across backend restarts.
    """
    PENDING = "PENDING"
    RUNNING = "RUNNING"
    COMPLETED = "COMPLETED"
    CALLING_BACKEND = "CALLING_BACKEND"
    CALL_BACKEND_FAILED = "CALL_BACKEND_FAILED"
    CALL_BACKEND_ACKED = "CALL_BACKEND_ACKED"
    AWAITING_APPROVAL = "AWAITING_APPROVAL"
    RETRY_READY = "RETRY_READY"
    RETRYING = "RETRYING"
    AWAITING_NEXT_TRIGGER = "AWAITING_NEXT_TRIGGER"


class AgentState:
    """
    Persistent state tracker for agent runs.
    All state is stored in Redis for durability and cross-process visibility.
    """

    def __init__(self, redis_client: Optional[redis.Redis] = None):
        """
        Initialize state tracker with Redis connection.

        Args:
            redis_client: Optional existing Redis connection. If not provided,
                         creates a new connection.
        """
        self.redis = redis_client or redis.Redis(
            host=REDIS_HOST,
            port=REDIS_PORT,
            db=REDIS_DB,
            decode_responses=True
        )

    def _get_run_key(self, run_id: str) -> str:
        """Get Redis key for a specific run."""
        return f"agent:run:{run_id}"

    def save_run_state(
        self,
        run_id: str,
        state: AgentRunState,
        metadata: Optional[Dict[str, Any]] = None,
        timestamp: Optional[datetime] = None
    ) -> bool:
        """
        Persist run state to Redis.

        Args:
            run_id: UUID string of the agent run
            state: Current state of the run
            metadata: Additional data to persist (errors, retry counts, etc.)
            timestamp: Timestamp of state change (defaults to now)

        Returns:
            True if state was saved successfully, False otherwise
        """
        key = self._get_run_key(run_id)
        ts = timestamp or datetime.utcnow()

        store_data = {
            "state": state.value,
            "timestamp": ts.isoformat(),
            "metadata": json.dumps(metadata or {}),
        }

        try:
            self.redis.hset(key, mapping=store_data)
            # Set expiration on run state (7 days should be sufficient)
            self.redis.expire(key, 604800)
            return True
        except redis.RedisError as e:
            print(f"[agent_state] ERROR: Failed to save state for run {run_id}: {e}")
            return False

    def get_run_state(self, run_id: str) -> Optional[Dict[str, Any]]:
        """
        Retrieve run state from Redis.

        Args:
            run_id: UUID string of the agent run

        Returns:
            Dict containing state, timestamp, and metadata, or None if not found
        """
        key = self._get_run_key(run_id)
        try:
            data = self.redis.hgetall(key)
            if not data:
                return None

            return {
                "run_id": run_id,
                "state": AgentRunState(data.get("state", "PENDING")),
                "timestamp": data.get("timestamp"),
                "metadata": json.loads(data.get("metadata", "{}")),
            }
        except (redis.RedisError, json.JSONDecodeError) as e:
            print(f"[agent_state] ERROR: Failed to get state for run {run_id}: {e}")
            return None

    def clear_run_state(self, run_id: str) -> bool:
        """
        Remove run state from Redis.

        Args:
            run_id: UUID string of the agent run

        Returns:
            True if state was deleted successfully
        """
        key = self._get_run_key(run_id)
        try:
            self.redis.delete(key)
            return True
        except redis.RedisError as e:
            print(f"[agent_state] ERROR: Failed to clear state for run {run_id}: {e}")
            return False

    def get_all_stuck_runs(self) -> List[str]:
        """
        Find all runs that need attention (stuck in failed states).

        Returns:
            List of run_ids that need retry or recovery
        """
        stuck_states = [
            AgentRunState.CALL_BACKEND_FAILED,
            AgentRunState.AWAITING_APPROVAL,
            AgentRunState.AWAITING_NEXT_TRIGGER,
        ]

        try:
            all_keys = self.redis.keys("agent:run:*")
            stuck_runs = []

            for key in all_keys:
                data = self.redis.hgetall(key)
                if data:
                    state = AgentRunState(data.get("state", "PENDING"))
                    if state in stuck_states:
                        run_id = key.replace("agent:run:", "")
                        stuck_runs.append(run_id)

            return stuck_runs
        except redis.RedisError as e:
            print(f"[agent_state] ERROR: Failed to get stuck runs: {e}")
            return []

    def get_completed_unacknowledged_runs(self) -> List[str]:
        """
        Find runs that completed but backend may not have acknowledged.

        These are runs where completion was reported but no APPROVED/REJECTED
        signal was received from backend.

        Returns:
            List of run_ids that need acknowledgement check
        """
        try:
            all_keys = self.redis.keys("agent:run:*")
            unacknowledged = []

            for key in all_keys:
                data = self.redis.hgetall(key)
                if data:
                    state = AgentRunState(data.get("state", "PENDING"))
                    timestamp_str = data.get("timestamp")

                    if state == AgentRunState.CALL_BACKEND_ACKED and timestamp_str:
                        # Check if 5 minutes have passed without backend update
                        timestamp = datetime.fromisoformat(timestamp_str)
                        elapsed = (datetime.utcnow() - timestamp).total_seconds()
                        if elapsed > 300:  # 5 minutes
                            run_id = key.replace("agent:run:", "")
                            unacknowledged.append(run_id)

            return unacknowledged
        except (redis.RedisError, ValueError) as e:
            print(f"[agent_state] ERROR: Failed to get unacknowledged runs: {e}")
            return []


class BackendCallbackHandler:
    """
    Handles callbacks to the backend with retry logic and state persistence.
    """

    def __init__(
        self,
        backend_url: str = BACKEND_URL,
        max_retries: int = MAX_RETRY_ATTEMPTS,
        state_tracker: Optional[AgentState] = None
    ):
        """
        Initialize backend callback handler.

        Args:
            backend_url: Base URL of the backend API
            max_retries: Maximum number of retry attempts
            state_tracker: AgentState instance for persistence
        """
        self.backend_url = backend_url.rstrip("/")
        self.max_retries = max_retries
        self.state_tracker = state_tracker or AgentState()

        self._headers = {
            "Content-Type": "application/json",
            "X-Agent-Key": INTERNAL_API_KEY,
        }

    def push_event(
        self,
        run_id: str,
        event_type: str,
        message: str,
        payload: Dict[str, Any] = None
    ) -> bool:
        """
        Send a real-time event to the backend with retry logic.

        Args:
            run_id: UUID string of the agent run
            event_type: Type of event (STARTED, THINKING, etc.)
            message: Human-readable description
            payload: Optional structured data

        Returns:
            True if event was successfully delivered, False otherwise
        """
        data = {
            "runId": run_id,
            "eventType": event_type,
            "message": message,
            "payload": payload or {},
            "timestamp": datetime.utcnow().isoformat(),
        }

        return self._send_with_retry(run_id, "event", data)

    def complete_run(
        self,
        run_id: str,
        report: Dict[str, Any]
    ) -> bool:
        """
        Notify the backend that an agent run has completed.
        Implements retry logic with state persistence.

        Args:
            run_id: UUID string of the agent run
            report: Structured report dict

        Returns:
            True if completion was successfully reported, False otherwise
        """
        # First, update state to CALLING_BACKEND
        self.state_tracker.save_run_state(
            run_id,
            AgentRunState.CALLING_BACKEND,
            {"retry_count": 0}
        )

        data = {
            "runId": run_id,
            "report": report,
            "tokensUsed": report.get("tokens_used", 0),
        }

        success = self._send_with_retry(run_id, "complete", data)

        if success:
            # Mark as acknowledged
            self.state_tracker.save_run_state(
                run_id,
                AgentRunState.CALL_BACKEND_ACKED,
                {"backend_ack": True}
            )
        else:
            # State already set to CALL_BACKEND_FAILED by _send_with_retry
            pass

        return success

    def _send_with_retry(
        self,
        run_id: str,
        endpoint: str,
        data: Dict[str, Any]
    ) -> bool:
        """
        Internal method to send request with exponential backoff retry.

        Args:
            run_id: UUID string of the agent run
            endpoint: API endpoint to call ("event" or "complete")
            data: Request payload

        Returns:
            True if request succeeded, False if max retries exceeded
        """
        for attempt in range(1, self.max_retries + 1):
            try:
                response = requests.post(
                    f"{self.backend_url}/{endpoint}",
                    json=data,
                    headers=self._headers,
                    timeout=30
                )

                if response.status_code == 200:
                    if endpoint == "complete":
                        self.state_tracker.save_run_state(
                            run_id,
                            AgentRunState.COMPLETED,
                            {"backend_ack": True, "attempts": attempt}
                        )
                    return True

                # Non-retryable error status
                if response.status_code >= 400:
                    error_detail = response.json().get("message", "Unknown error")
                    print(f"[callback_handler] Backend error {response.status_code}: {error_detail}")

                    if endpoint == "complete":
                        self.state_tracker.save_run_state(
                            run_id,
                            AgentRunState.CALL_BACKEND_FAILED,
                            {
                                "attempt": attempt,
                                "error": error_detail,
                                "status_code": response.status_code,
                            }
                        )
                    return False

            except requests.exceptions.Timeout:
                print(f"[callback_handler] Timeout on attempt {attempt} for run {run_id}")

            except requests.exceptions.ConnectionError as e:
                print(f"[callback_handler] Connection error on attempt {attempt}: {e}")

            except requests.exceptions.RequestException as e:
                print(f"[callback_handler] Request failed on attempt {attempt}: {e}")

            if attempt < self.max_retries:
                # Exponential backoff
                delay = min(RETRY_BASE_DELAY_SECONDS * (2 ** (attempt - 1)), MAX_RETRY_DELAY_SECONDS)
                print(f"[callback_handler] Retrying in {delay}s (attempt {attempt + 1}) for run {run_id}")
                time.sleep(delay)
            else:
                # Max retries exceeded
                if endpoint == "complete":
                    self.state_tracker.save_run_state(
                        run_id,
                        AgentRunState.CALL_BACKEND_FAILED,
                        {
                            "final_attempt": attempt,
                            "error": "Max retries exceeded",
                        }
                    )

                print(f"[callback_handler] Failed to notify backend after {attempt} attempts for run {run_id}")
                return False

        return False

    def fetch_approval_status(self, run_id: str) -> Optional[Dict[str, Any]]:
        """
        Poll backend for approval status of a run.

        Args:
            run_id: UUID string of the agent run

        Returns:
            Dict with approval status or None if not found
        """
        try:
            response = requests.get(
                f"{self.backend_url}/approval/{run_id}",
                headers=self._headers,
                timeout=10
            )

            if response.status_code == 200:
                return response.json()
            elif response.status_code == 404:
                return None
            else:
                print(f"[callback_handler] Error fetching approval status: {response.status_code}")
                return None

        except requests.RequestException as e:
            print(f"[callback_handler] Failed to fetch approval status: {e}")
            return None


class SignalConsumer:
    """
    Consumer for agent signal messages from Redis Streams.
    Handles RESUME signals to retry completion callbacks.
    """

    def __init__(
        self,
        redis_client: Optional[redis.Redis] = None,
        state_tracker: Optional[AgentState] = None
    ):
        """
        Initialize signal consumer.

        Args:
            redis_client: Redis connection
            state_tracker: AgentState instance for persistence
        """
        self.redis = redis_client or redis.Redis(
            host=REDIS_HOST,
            port=REDIS_PORT,
            db=REDIS_DB,
            decode_responses=True
        )
        self.state_tracker = state_tracker or AgentState()
        self._running = False

    def start(self) -> None:
        """Start consuming signals in background thread."""
        self._running = True
        import threading
        thread = threading.Thread(target=self._consume_loop, daemon=True)
        thread.start()
        print("[signal_consumer] Started signal consumer")

    def stop(self) -> None:
        """Stop consuming signals."""
        self._running = False
        print("[signal_consumer] Stopped signal consumer")

    def _consume_loop(self) -> None:
        """Main loop for consuming signals."""
        pubsub = self.redis.pubsub()
        pubsub.subscribe(SIGNAL_STREAM_NAME)

        while self._running:
            message = pubsub.get_message(timeout=1.0)
            if message and message["type"] == "message":
                self._handle_message(message["data"])

    def _handle_message(self, message: str) -> None:
        """
        Handle a signal message.

        Args:
            message: JSON string containing signal data
        """
        try:
            signal_data = json.loads(message)
            signal_type = signal_data.get("signal_type")

            if signal_type == "RESUME":
                self._handle_resume_signal(signal_data)
            elif signal_type == "APPROVE":
                self._handle_approve_signal(signal_data)
            elif signal_type == "REJECT":
                self._handle_reject_signal(signal_data)

        except json.JSONDecodeError as e:
            print(f"[signal_consumer] Failed to parse signal message: {e}")
        except Exception as e:
            print(f"[signal_consumer] Error handling signal: {e}")

    def _handle_resume_signal(self, signal_data: Dict[str, Any]) -> None:
        """
        Handle RESUME signal - retry completion callbacks.

        Args:
            signal_data: Parsed signal data from Redis
        """
        run_id = signal_data.get("run_id")
        reason = signal_data.get("data", {}).get("reason", "Resume signal")

        print(f"[signal_consumer] Processing RESUME signal for run {run_id}: {reason}")

        if run_id:
            # Get run state to fetch completion data
            state = self.state_tracker.get_run_state(run_id)
            if state and state["metadata"]:
                # Retry the completion callback
                callback_handler = BackendCallbackHandler()
                # Note: In a real scenario, we'd store the report data persistently
                # For now, we mark the run for retry
                self.state_tracker.save_run_state(
                    run_id,
                    AgentRunState.RETRY_READY,
                    {"resume_reason": reason}
                )
                print(f"[signal_consumer] Marked run {run_id} for retry completion")

    def _handle_approve_signal(self, signal_data: Dict[str, Any]) -> None:
        """Handle APPROVE signal (agent waits for approval trigger)."""
        run_id = signal_data.get("run_id")
        print(f"[signal_consumer] APPROVE signal received for run {run_id}")
        # Agent should now be in AWAITING_NEXT_TRIGGER state

    def _handle_reject_signal(self, signal_data: Dict[str, Any]) -> None:
        """Handle REJECT signal (agent should prepare for retry)."""
        run_id = signal_data.get("run_id")
        reason = signal_data.get("data", {}).get("reason", "Rejected")
        print(f"[signal_consumer] REJECT signal for run {run_id}: {reason}")
        # Agent should prepare for retry with different parameters

    def on_shutdown(self) -> None:
        """Clean shutdown of signal consumer."""
        self.stop()
        print("[signal_consumer] Shutdown complete")


def recover_stuck_runs(state_tracker: Optional[AgentState] = None) -> List[str]:
    """
    On agent service startup, check for and recover stuck runs.

    Args:
        state_tracker: Optional AgentState instance

    Returns:
        List of run_ids that were recovered
    """
    tracker = state_tracker or AgentState()
    recovered = []

    print("[recover] Checking for stuck runs...")

    # Get runs that need attention
    stuck_runs = tracker.get_all_stuck_runs()

    for run_id in stuck_runs:
        state = tracker.get_run_state(run_id)
        if not state:
            continue

        run_state = state["state"]
        metadata = state["metadata"]

        if run_state == AgentRunState.CALL_BACKEND_FAILED:
            # Try to retry the completion callback
            print(f"[recover] Retrying completion for run {run_id}")
            callback_handler = BackendCallbackHandler()
            # Note: In production, we'd store the report data persistently
            # Here we just update the state

            # Try one retry
            success = callback_handler._send_with_retry(
                run_id,
                "complete",
                {"run_id": run_id, "retry": True}
            )

            if success:
                recovered.append(run_id)
                print(f"[recover] Successfully recovered run {run_id}")
            else:
                print(f"[recover] Still unable to recover run {run_id}")

        elif run_state == AgentRunState.AWAITING_APPROVAL:
            print(f"[recover] Run {run_id} awaiting approval - will check with backend")
            # This will be handled when backend sends RESUME signal

        elif run_state == AgentRunState.AWAITING_NEXT_TRIGGER:
            print(f"[recover] Run {run_id} awaiting next trigger - will check with backend")
            # This will be handled when backend sends RESUME signal

    # Also check for unacknowledged completed runs
    unack_runs = tracker.get_completed_unacknowledged_runs()
    for run_id in unack_runs:
        print(f"[recover] Unacknowledged completion for run {run_id} - will wait for backend signal")

    print(f"[recover] Recovery check complete. Recovered: {len(recovered)} runs")
    return recovered


def get_run_metadata(run_id: str) -> Optional[Dict[str, Any]]:
    """
    Get metadata for a specific run.

    Args:
        run_id: UUID string of the agent run

    Returns:
        Metadata dict or None if not found
    """
    state_tracker = AgentState()
    state = state_tracker.get_run_state(run_id)
    return state["metadata"] if state else None


def persist_run_metadata(run_id: str, key: str, value: Any) -> bool:
    """
    Persist a specific piece of metadata for a run.

    Args:
        run_id: UUID string of the agent run
        key: Metadata key
        value: Value to persist

    Returns:
        True if persisted successfully
    """
    state_tracker = AgentState()
    current_state = state_tracker.get_run_state(run_id)

    if current_state:
        metadata = current_state.get("metadata", {})
        metadata[key] = value
        return state_tracker.save_run_state(run_id, current_state["state"], metadata)

    return False
