"""
Unit tests for crew_runner.py

Tests the pipeline routing, context building, output writing, and report parsing
using mocks so no real LLM or filesystem calls are required.
"""

import json
import os
import pathlib
import sys
from unittest.mock import MagicMock, patch

import pytest

sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from crew_runner import (
    _build_project_context,
    _write_output_to_workspace,
    _parse_result_to_report,
    run_agent_pipeline,
    AGENT_DEFINITIONS,
    TASK_DESCRIPTIONS,
)


# ---------------------------------------------------------------------------
# _build_project_context
# ---------------------------------------------------------------------------

class TestBuildProjectContext:
    def test_includes_tech_stack(self):
        config = {"tech_stack": ["Python", "FastAPI"], "output_language": "en"}
        with patch("crew_runner.get_knowledge_context", return_value=""):
            ctx = _build_project_context(config)
        assert "Python" in ctx
        assert "FastAPI" in ctx

    def test_includes_previous_outputs(self):
        config = {
            "tech_stack": [],
            "output_language": "en",
            "previous_outputs": {"pm": "User stories here"},
        }
        with patch("crew_runner.get_knowledge_context", return_value=""):
            ctx = _build_project_context(config)
        assert "pm" in ctx
        assert "User stories here" in ctx

    def test_includes_knowledge_context(self):
        config = {"tech_stack": [], "output_language": "en"}
        with patch("crew_runner.get_knowledge_context", return_value="KNOWLEDGE_SNIPPET"):
            ctx = _build_project_context(config)
        assert "KNOWLEDGE_SNIPPET" in ctx

    def test_includes_coding_style(self):
        config = {
            "tech_stack": [],
            "output_language": "en",
            "coding_style_guide": "Follow PEP-8",
        }
        with patch("crew_runner.get_knowledge_context", return_value=""):
            ctx = _build_project_context(config)
        assert "PEP-8" in ctx


# ---------------------------------------------------------------------------
# _write_output_to_workspace
# ---------------------------------------------------------------------------

class TestWriteOutputToWorkspace:
    def test_writes_file_and_returns_path(self, tmp_path):
        config = {"workspace_path": str(tmp_path), "module_name": "Login Feature"}
        path = _write_output_to_workspace("some content", "pm", config)

        assert path is not None
        assert pathlib.Path(path).exists()
        assert pathlib.Path(path).read_text() == "some content"

    def test_returns_none_when_no_workspace(self):
        config = {}
        path = _write_output_to_workspace("content", "pm", config)
        assert path is None

    def test_sanitises_module_name(self, tmp_path):
        config = {"workspace_path": str(tmp_path), "module_name": "User Auth SSO"}
        path = _write_output_to_workspace("x", "qa", config)
        # spaces replaced with underscores, lowercased
        assert "user_auth_sso" in path


# ---------------------------------------------------------------------------
# _parse_result_to_report
# ---------------------------------------------------------------------------

class TestParseResultToReport:
    def test_summary_from_first_line(self):
        result = "Line one is the summary\nLine two"
        report = _parse_result_to_report(result, "pm", {})
        assert report["summary"] == "Line one is the summary"

    def test_next_steps_always_empty(self):
        # Backend workflow orchestrator handles sequencing — next_steps is always []
        for agent in ("pm", "architect", "dev", "qa", "docs", "coder", "reviewer"):
            report = _parse_result_to_report("summary", agent, {})
            assert report["next_steps"] == [], \
                f"Expected next_steps == [] for agent '{agent}', got {report['next_steps']}"

    def test_no_next_step_for_docs(self):
        # docs was previously the terminal node; now all agents return []
        report = _parse_result_to_report("summary", "docs", {})
        assert report["next_steps"] == []

    def test_confidence_score_absent_in_fallback(self):
        # Fallback parser does not emit confidence_score — agents must set it explicitly
        report = _parse_result_to_report("summary", "pm", {})
        assert "confidence_score" not in report

    def test_deliverable_uses_file_path(self, tmp_path):
        file_path = str(tmp_path / "pm_output.md")
        report = _parse_result_to_report("summary", "pm", {}, file_path=file_path)
        assert report["deliverables"][0]["file_path"] == file_path


# ---------------------------------------------------------------------------
# run_agent_pipeline routing
# ---------------------------------------------------------------------------

class TestRunAgentPipelineRouting:
    def test_routes_cli_mode(self):
        config = {"run_id": "r1", "agent_name": "pm", "invocation_mode": "CLI"}
        with patch("crew_runner._run_cli_pipeline") as mock_cli:
            run_agent_pipeline(config)
        mock_cli.assert_called_once_with(config)

    def test_routes_api_mode(self):
        config = {"run_id": "r1", "agent_name": "pm", "invocation_mode": "API"}
        with patch("crew_runner._run_crewai_pipeline") as mock_crewai:
            run_agent_pipeline(config)
        mock_crewai.assert_called_once_with(config)

    def test_routes_sdk_mode(self):
        config = {"run_id": "r1", "agent_name": "pm", "invocation_mode": "SDK"}
        with patch("crew_runner._run_crewai_pipeline") as mock_crewai:
            run_agent_pipeline(config)
        mock_crewai.assert_called_once_with(config)

    def test_defaults_to_cli(self):
        config = {"run_id": "r1", "agent_name": "pm"}
        with patch("crew_runner._run_cli_pipeline") as mock_cli:
            run_agent_pipeline(config)
        mock_cli.assert_called_once()


# ---------------------------------------------------------------------------
# Agent definitions completeness check
# ---------------------------------------------------------------------------

# Legacy fallback definitions — kept for backward-compatibility with configs that
# don't supply an agent_template.  New agents should always include agent_template.
class TestLegacyAgentDefinitions:
    def test_all_agents_defined(self):
        for agent in ("pm", "architect", "dev", "qa", "docs"):
            assert agent in AGENT_DEFINITIONS
            assert agent in TASK_DESCRIPTIONS

    def test_backstory_template_has_context_placeholder(self):
        for name, defn in AGENT_DEFINITIONS.items():
            assert "{context}" in defn["backstory_template"], \
                f"{name} backstory_template missing {{context}} placeholder"
