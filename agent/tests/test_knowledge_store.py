"""
Unit tests for knowledge_store.py

Uses a temporary directory so no real .knowledge files are created/polluted.
"""

import json
import os
import sys
import tempfile
import pathlib

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))


@pytest.fixture(autouse=True)
def isolated_knowledge_dir(monkeypatch, tmp_path):
    """Redirect all knowledge file I/O to a temp directory."""
    monkeypatch.setenv("KNOWLEDGE_DIR", str(tmp_path))
    # Re-import to pick up the env var change
    import importlib
    import knowledge_store
    importlib.reload(knowledge_store)
    return tmp_path


def _make_task(module_id="aaaabbbb-cccc-dddd-eeee-ffffgggghhhh", agent="pm"):
    return {
        "module_id": module_id,
        "agent_name": agent,
        "tech_stack": ["Python", "PostgreSQL"],
        "output_language": "en",
    }


def _make_report(summary="All done", confidence=80.0):
    return {
        "summary": summary,
        "confidence_score": confidence,
        "issues_found": [{"severity": "MINOR", "description": "Unused import"}],
    }


class TestRecordRun:
    def test_creates_knowledge_file(self, tmp_path):
        import knowledge_store
        task = _make_task()
        knowledge_store.record_run(task, _make_report())

        files = list(tmp_path.iterdir())
        assert len(files) == 1
        data = json.loads(files[0].read_text())
        assert len(data["runs"]) == 1
        assert data["runs"][0]["agent"] == "pm"

    def test_accumulates_multiple_runs(self, tmp_path):
        import knowledge_store
        task = _make_task()
        for i in range(3):
            knowledge_store.record_run(task, _make_report(summary=f"Run {i}"))

        files = list(tmp_path.iterdir())
        data = json.loads(files[0].read_text())
        assert len(data["runs"]) == 3

    def test_caps_runs_at_50(self, tmp_path):
        import knowledge_store
        task = _make_task()
        for i in range(55):
            knowledge_store.record_run(task, _make_report(summary=f"run-{i}"))

        data = json.loads(list(tmp_path.iterdir())[0].read_text())
        assert len(data["runs"]) == 50

    def test_stores_issues_truncated(self, tmp_path):
        import knowledge_store
        task = _make_task()
        report = {
            "summary": "ok",
            "confidence_score": 70,
            "issues_found": [
                {"severity": "CRITICAL", "description": "Bad thing " * 20}
            ],
        }
        knowledge_store.record_run(task, report)
        data = json.loads(list(tmp_path.iterdir())[0].read_text())
        assert len(data["runs"][0]["issues"][0]["desc"]) <= 120


class TestGetContext:
    def test_returns_empty_for_fresh_project(self):
        import knowledge_store
        task = _make_task(module_id="00000000-0000-0000-0000-000000000000")
        ctx = knowledge_store.get_context(task)
        assert ctx == ""

    def test_returns_knowledge_after_run(self, tmp_path):
        import knowledge_store
        task = _make_task()
        knowledge_store.record_run(task, _make_report(summary="PM found 3 stories"))
        ctx = knowledge_store.get_context(task)
        assert "PM found 3 stories" in ctx
        assert "Knowledge from previous pm runs" in ctx

    def test_returns_empty_for_different_agent_type(self, tmp_path):
        import knowledge_store
        task_pm = _make_task(agent="pm")
        task_arch = _make_task(agent="architect")
        knowledge_store.record_run(task_pm, _make_report(summary="PM output"))
        ctx = knowledge_store.get_context(task_arch)
        assert ctx == ""
