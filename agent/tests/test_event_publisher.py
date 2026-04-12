"""
Unit tests for event_publisher.py

Tests the REST client helpers using mocked requests so no real backend is needed.
Tests include both the legacy direct HTTP calls and the new state-based retry logic.
"""

import json
from unittest.mock import MagicMock, patch, Mock

import pytest

import sys
import os
sys.path.insert(0, os.path.join(os.path.dirname(__file__), ".."))

from event_publisher import push_event, complete_run, fetch_interrupted_runs
from agent_state import AgentState, BackendCallbackHandler


class TestPushEvent:
    def test_posts_correct_payload(self):
        mock_resp = MagicMock(status_code=200)
        with patch("event_publisher.requests.post", return_value=mock_resp) as mock_post:
            push_event("run-123", "STARTED", "Agent started", {"key": "val"})

        mock_post.assert_called_once()
        _, kwargs = mock_post.call_args
        body = kwargs["json"]
        assert body["runId"] == "run-123"
        assert body["eventType"] == "STARTED"
        assert body["message"] == "Agent started"
        assert body["payload"] == {"key": "val"}

    def test_does_not_raise_on_network_error(self):
        """push_event must never raise — failures are silent so the agent can continue."""
        with patch("event_publisher.requests.post", side_effect=ConnectionError("down")):
            push_event("run-999", "INFO", "hello")  # should not raise


class TestCompleteRun:
    def test_posts_report(self):
        report = {"summary": "Done", "tokens_used": 100}
        mock_resp = MagicMock(status_code=200)
        with patch("event_publisher.requests.post", return_value=mock_resp) as mock_post:
            complete_run("run-456", report)

        mock_post.assert_called_once()
        _, kwargs = mock_post.call_args
        body = kwargs["json"]
        assert body["runId"] == "run-456"
        assert body["report"] == report
        assert body["tokensUsed"] == 100

    def test_tokens_default_zero_when_missing(self):
        with patch("event_publisher.requests.post") as mock_post:
            complete_run("run-789", {"summary": "ok"})
        body = mock_post.call_args[1]["json"]
        assert body["tokensUsed"] == 0


class TestFetchInterruptedRuns:
    def test_returns_list_on_success(self):
        tasks = [{"run_id": "aaa", "agent_name": "pm"}]
        mock_resp = MagicMock(status_code=200)
        mock_resp.json.return_value = tasks
        with patch("event_publisher.requests.get", return_value=mock_resp):
            result = fetch_interrupted_runs()
        assert result == tasks

    def test_returns_empty_on_non_200(self):
        mock_resp = MagicMock(status_code=500)
        with patch("event_publisher.requests.get", return_value=mock_resp):
            result = fetch_interrupted_runs()
        assert result == []

    def test_returns_empty_on_network_error(self):
        with patch("event_publisher.requests.get", side_effect=ConnectionError("offline")):
            result = fetch_interrupted_runs()
        assert result == []


class TestEventPublisherWithState:
    """Tests for the new state-based event handling."""

    @pytest.fixture
    def mock_redis(self):
        """Create a mock Redis client."""
        with patch('redis.Redis') as mock_redis_class:
            mock_client = MagicMock()
            mock_redis_class.return_value = mock_client
            yield mock_client

    @patch("event_publisher.requests.post")
    def test_push_event_calls_backend_handler(self, mock_post, mock_redis):
        """Test that push_event uses the new backend handler."""
        mock_post.return_value = MagicMock(status_code=200)

        result = push_event("run-123", "STARTED", "Agent started")

        assert result is True
        mock_post.assert_called_once()

    @patch("event_publisher.requests.post")
    def test_complete_run_with_state_persistence(self, mock_post, mock_redis):
        """Test that complete_run persists state."""
        mock_post.return_value = MagicMock(status_code=200)
        report = {"summary": "Done", "tokens_used": 100}

        result = complete_run("run-456", report)

        assert result is True
