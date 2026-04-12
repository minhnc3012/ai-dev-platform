"""
Agent service entry point.
Listens on Redis for task dispatch messages from the Spring Boot backend
and executes agent pipelines via CrewAI.

On startup, recovers any runs that were RUNNING when the service last stopped,
so interrupted work is resumed automatically after crashes or restarts.

Duplicate-run protection: a global set tracks run_ids currently in-flight.
Any attempt to start the same run_id twice is silently ignored.
"""

import json
import os
import signal
import sys
import threading
import time

import redis
from dotenv import load_dotenv

load_dotenv()

from crew_runner import run_agent_pipeline
from event_publisher import push_event, fetch_interrupted_runs, complete_run
from agent_state import AgentState, recover_stuck_runs, SignalConsumer

REDIS_HOST = os.getenv("REDIS_HOST", "localhost")
REDIS_PORT = int(os.getenv("REDIS_PORT", "6379"))
REDIS_DB = int(os.getenv("REDIS_DB", "0"))

TASK_CHANNEL = "agent:tasks"
NEXT_CHANNEL = "agent:next"

_shutdown = threading.Event()

# Tracks run_ids currently being executed in this process.
# Prevents the same run from being started twice (e.g., via recovery + Redis message race).
_active_runs: set[str] = set()
_active_runs_lock = threading.Lock()


def _is_run_active(run_id: str) -> bool:
    with _active_runs_lock:
        return run_id in _active_runs


def _mark_run_active(run_id: str) -> bool:
    """Returns True if we successfully claimed the run, False if it was already active."""
    with _active_runs_lock:
        if run_id in _active_runs:
            return False
        _active_runs.add(run_id)
        return True


def _mark_run_done(run_id: str) -> None:
    with _active_runs_lock:
        _active_runs.discard(run_id)


def handle_task_message(message: dict) -> None:
    """Process a task message dispatched from the orchestrator for the first agent."""
    if message.get("type") != "message":
        return
    data_str = message.get("data", "")
    try:
        task_config = json.loads(data_str)
        run_id = task_config.get("run_id", "unknown")
        agent_name = task_config.get("agent_name", "unknown")

        if not _mark_run_active(run_id):
            print(f"[main] SKIP duplicate task: run_id={run_id} already in-flight")
            return

        print(f"[main] Received task: run_id={run_id}, agent={agent_name}")
        t = threading.Thread(
            target=_run_with_error_handling,
            args=(task_config,),
            daemon=True,
        )
        t.start()
    except json.JSONDecodeError as exc:
        print(f"[main] ERROR: Failed to parse task message: {exc}")


def handle_next_message(message: dict) -> None:
    """
    Process a 'trigger next agent' message.
    The backend sends the full task config so the agent executes immediately.
    """
    if message.get("type") != "message":
        return
    data_str = message.get("data", "")
    try:
        task_config = json.loads(data_str)
        run_id = task_config.get("run_id", "unknown")
        agent_name = task_config.get("agent_name", "unknown")

        if not _mark_run_active(run_id):
            print(f"[main] SKIP duplicate next: run_id={run_id} already in-flight")
            return

        print(f"[main] Next agent triggered: run_id={run_id}, agent={agent_name}")
        t = threading.Thread(
            target=_run_with_error_handling,
            args=(task_config,),
            daemon=True,
        )
        t.start()
    except json.JSONDecodeError as exc:
        print(f"[main] ERROR: Failed to parse next message: {exc}")


def _run_with_error_handling(task_config: dict) -> None:
    run_id = task_config.get("run_id", "unknown")
    try:
        run_agent_pipeline(task_config)
    except Exception as exc:
        print(f"[main] ERROR: Agent pipeline failed for run_id={run_id}: {exc}")
        try:
            push_event(run_id, "ERROR", f"Pipeline execution failed: {str(exc)[:500]}")
        except Exception:
            pass
    finally:
        _mark_run_done(run_id)


def _recover_interrupted_runs() -> None:
    """
    On startup, fetch all runs that were RUNNING when the service last crashed
    and re-execute each one in a background thread.

    Also recovers runs that completed but backend didn't acknowledge,
    ensuring workflow continuity across service restarts.
    """
    # First, use the new state-based recovery
    recovered = recover_stuck_runs()

    print("[main] Checking for interrupted runs to recover...")
    interrupted = fetch_interrupted_runs()
    if not interrupted:
        print("[main] No interrupted runs found via backend API.")

    print(f"[main] Recovering {len(interrupted)} interrupted run(s)...")
    for task_config in interrupted:
        run_id = task_config.get("run_id", "unknown")
        agent_name = task_config.get("agent_name", "unknown")

        if not _mark_run_active(run_id):
            print(f"[main] SKIP recovery run_id={run_id} — already claimed")
            continue

        print(f"[main] Resuming run_id={run_id}, agent={agent_name}")
        time.sleep(0.5)  # small stagger to avoid thundering herd
        t = threading.Thread(
            target=_run_with_error_handling,
            args=(task_config,),
            daemon=True,
        )
        t.start()


def main() -> None:
    print("[main] Agent service starting...")
    print(f"[main] Connecting to Redis at {REDIS_HOST}:{REDIS_PORT}")

    r = redis.Redis(host=REDIS_HOST, port=REDIS_PORT, db=REDIS_DB, decode_responses=True)

    try:
        r.ping()
        print("[main] Redis connection established")
    except redis.ConnectionError as exc:
        print(f"[main] FATAL: Cannot connect to Redis: {exc}")
        sys.exit(1)

    # Initialize signal consumer for RESUME/APPROVE/REJECT signals
    signal_consumer = SignalConsumer(redis_client=r)
    signal_consumer.start()

    # Subscribe FIRST so we don't miss any tasks dispatched during recovery window
    pubsub = r.pubsub()
    pubsub.subscribe(**{
        TASK_CHANNEL: handle_task_message,
        NEXT_CHANNEL: handle_next_message,
    })
    print(f"[main] Subscribed to channels: {TASK_CHANNEL}, {NEXT_CHANNEL}")

    # Then recover interrupted runs (deduplication protects against double-start)
    _recover_interrupted_runs()

    print("[main] Agent service ready. Waiting for tasks...")

    def handle_signal(signum, frame):
        print("\n[main] Shutdown signal received. Exiting...")
        _shutdown.set()

    signal.signal(signal.SIGINT, handle_signal)
    signal.signal(signal.SIGTERM, handle_signal)

    try:
        while not _shutdown.is_set():
            pubsub.get_message(timeout=1.0)
    finally:
        pubsub.unsubscribe()
        pubsub.close()
        signal_consumer.on_shutdown()
        print("[main] Agent service stopped.")


if __name__ == "__main__":
    main()
