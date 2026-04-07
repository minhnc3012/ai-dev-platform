"""
Unit tests for main.py
Tests message handlers, duplicate-run protection, and recovery logic.
"""

import json
import os
import sys
import threading
from unittest.mock import MagicMock, patch

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

import main as agent_main


@pytest.fixture(autouse=True)
def clear_active_runs():
    """Reset the global active-runs set before each test."""
    with agent_main._active_runs_lock:
        agent_main._active_runs.clear()
    yield
    with agent_main._active_runs_lock:
        agent_main._active_runs.clear()


class TestMarkRunActive:
    def test_first_claim_returns_true(self):
        assert agent_main._mark_run_active("run-1") is True

    def test_duplicate_claim_returns_false(self):
        agent_main._mark_run_active("run-dup")
        assert agent_main._mark_run_active("run-dup") is False

    def test_done_allows_reclaim(self):
        agent_main._mark_run_active("run-x")
        agent_main._mark_run_done("run-x")
        assert agent_main._mark_run_active("run-x") is True


class TestHandleTaskMessage:
    def test_dispatches_thread_on_valid_message(self):
        task = {"run_id": "run-1", "agent_name": "pm"}
        message = {"type": "message", "data": json.dumps(task)}

        with patch("main.threading.Thread") as mock_thread_cls:
            mock_thread = MagicMock()
            mock_thread_cls.return_value = mock_thread
            agent_main.handle_task_message(message)

        mock_thread_cls.assert_called_once()
        mock_thread.start.assert_called_once()

    def test_skips_duplicate_run_id(self):
        task = {"run_id": "run-dup", "agent_name": "pm"}
        message = {"type": "message", "data": json.dumps(task)}

        agent_main._mark_run_active("run-dup")  # pre-claim

        with patch("main.threading.Thread") as mock_thread_cls:
            agent_main.handle_task_message(message)

        mock_thread_cls.assert_not_called()

    def test_ignores_non_message_type(self):
        with patch("main.threading.Thread") as mock_thread_cls:
            agent_main.handle_task_message({"type": "subscribe", "data": "{}"})
        mock_thread_cls.assert_not_called()

    def test_handles_bad_json_gracefully(self):
        agent_main.handle_task_message({"type": "message", "data": "NOT JSON {{"})


class TestHandleNextMessage:
    def test_dispatches_thread_with_full_config(self):
        task = {"run_id": "run-2", "agent_name": "architect", "module_id": "m1"}
        message = {"type": "message", "data": json.dumps(task)}

        with patch("main.threading.Thread") as mock_thread_cls:
            mock_thread = MagicMock()
            mock_thread_cls.return_value = mock_thread
            agent_main.handle_next_message(message)

        mock_thread_cls.assert_called_once()
        _, kwargs = mock_thread_cls.call_args
        assert kwargs["args"][0]["run_id"] == "run-2"
        mock_thread.start.assert_called_once()

    def test_skips_duplicate_run_id(self):
        task = {"run_id": "run-next-dup", "agent_name": "dev"}
        message = {"type": "message", "data": json.dumps(task)}

        agent_main._mark_run_active("run-next-dup")

        with patch("main.threading.Thread") as mock_thread_cls:
            agent_main.handle_next_message(message)

        mock_thread_cls.assert_not_called()

    def test_handles_bad_json_gracefully(self):
        agent_main.handle_next_message({"type": "message", "data": "bad json"})


class TestRunWithErrorHandling:
    def test_calls_pipeline_and_marks_done(self):
        task = {"run_id": "r1", "agent_name": "qa"}
        agent_main._mark_run_active("r1")

        with patch("main.run_agent_pipeline") as mock_pipeline:
            agent_main._run_with_error_handling(task)

        mock_pipeline.assert_called_once_with(task)
        assert not agent_main._is_run_active("r1")  # released after completion

    def test_pushes_error_event_on_failure(self):
        task = {"run_id": "r2", "agent_name": "dev"}
        agent_main._mark_run_active("r2")

        with patch("main.run_agent_pipeline", side_effect=RuntimeError("boom")), \
             patch("main.push_event") as mock_push:
            agent_main._run_with_error_handling(task)

        mock_push.assert_called_once()
        args = mock_push.call_args[0]
        assert args[0] == "r2"
        assert args[1] == "ERROR"
        assert "boom" in args[2]
        assert not agent_main._is_run_active("r2")

    def test_marks_done_even_if_push_fails(self):
        task = {"run_id": "r3", "agent_name": "docs"}
        agent_main._mark_run_active("r3")

        with patch("main.run_agent_pipeline", side_effect=RuntimeError("err")), \
             patch("main.push_event", side_effect=ConnectionError("dead")):
            agent_main._run_with_error_handling(task)

        assert not agent_main._is_run_active("r3")


class TestRecoverInterruptedRuns:
    def test_launches_thread_per_unclaimed_run(self):
        tasks = [
            {"run_id": "r10", "agent_name": "pm"},
            {"run_id": "r11", "agent_name": "architect"},
        ]
        with patch("main.fetch_interrupted_runs", return_value=tasks), \
             patch("main.time.sleep"), \
             patch("main.threading.Thread") as mock_thread_cls:
            mock_thread = MagicMock()
            mock_thread_cls.return_value = mock_thread
            agent_main._recover_interrupted_runs()

        assert mock_thread_cls.call_count == 2
        assert mock_thread.start.call_count == 2

    def test_skips_already_claimed_run(self):
        tasks = [{"run_id": "r-already", "agent_name": "pm"}]
        agent_main._mark_run_active("r-already")

        with patch("main.fetch_interrupted_runs", return_value=tasks), \
             patch("main.time.sleep"), \
             patch("main.threading.Thread") as mock_thread_cls:
            agent_main._recover_interrupted_runs()

        mock_thread_cls.assert_not_called()

    def test_does_nothing_when_no_interrupted_runs(self):
        with patch("main.fetch_interrupted_runs", return_value=[]), \
             patch("main.threading.Thread") as mock_thread_cls:
            agent_main._recover_interrupted_runs()
        mock_thread_cls.assert_not_called()
