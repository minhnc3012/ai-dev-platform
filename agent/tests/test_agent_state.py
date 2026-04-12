"""
Unit tests for agent state management module.

Tests cover:
- State persistence and retrieval
- State machine transitions
- Backend callback retry logic
- Signal consumer functionality
- Recovery of stuck runs
"""

import pytest
import json
import time
import requests
from datetime import datetime
from unittest.mock import Mock, patch, MagicMock
import redis

from agent_state import (
    AgentState,
    AgentRunState,
    BackendCallbackHandler,
    SignalConsumer,
    recover_stuck_runs,
    persist_run_metadata,
    get_run_metadata,
    BACKEND_URL,
    MAX_RETRY_ATTEMPTS,
)


class TestAgentState:
    """Tests for the AgentState class."""

    @pytest.fixture
    def mock_redis(self):
        """Create a mock Redis client."""
        with patch('redis.Redis') as mock_redis_class:
            mock_client = MagicMock(spec=redis.Redis)
            mock_redis_class.return_value = mock_client
            yield mock_client

    @pytest.fixture
    def state_tracker(self, mock_redis):
        """Create an AgentState instance with mock Redis."""
        return AgentState(mock_redis)

    def test_save_run_state_success(self, state_tracker, mock_redis):
        """Test saving run state to Redis."""
        run_id = "test-run-123"
        state = AgentRunState.RUNNING
        metadata = {"retry_count": 1}

        result = state_tracker.save_run_state(run_id, state, metadata)

        assert result is True
        mock_redis.hset.assert_called_once()
        mock_redis.expire.assert_called_once()

    def test_save_run_state_with_custom_timestamp(self, state_tracker, mock_redis):
        """Test saving run state with custom timestamp."""
        run_id = "test-run-456"
        state = AgentRunState.COMPLETED
        custom_timestamp = datetime(2024, 1, 1, 12, 0, 0)

        state_tracker.save_run_state(run_id, state, timestamp=custom_timestamp)

        call_args = mock_redis.hset.call_args
        stored_data = call_args[1]["mapping"]
        stored_timestamp = stored_data["timestamp"]

        assert custom_timestamp.isoformat() in stored_timestamp

    def test_save_run_state_no_metadata(self, state_tracker, mock_redis):
        """Test saving run state without metadata."""
        run_id = "test-run-789"
        state = AgentRunState.PENDING

        result = state_tracker.save_run_state(run_id, state)

        assert result is True
        call_args = mock_redis.hset.call_args
        stored_data = call_args[1]["mapping"]
        assert stored_data["metadata"] == "{}"

    def test_get_run_state_success(self, state_tracker, mock_redis):
        """Test retrieving run state from Redis."""
        run_id = "test-run-123"
        mock_redis.hgetall.return_value = {
            "state": AgentRunState.RUNNING.value,
            "timestamp": "2024-01-01T12:00:00",
            "metadata": json.dumps({"retry_count": 1}),
        }

        result = state_tracker.get_run_state(run_id)

        assert result is not None
        assert result["run_id"] == run_id
        assert result["state"] == AgentRunState.RUNNING
        assert result["metadata"]["retry_count"] == 1

    def test_get_run_state_not_found(self, state_tracker, mock_redis):
        """Test retrieving non-existent run state."""
        mock_redis.hgetall.return_value = {}

        result = state_tracker.get_run_state("non-existent-run")

        assert result is None

    def test_get_run_state_invalid_metadata(self, state_tracker, mock_redis):
        """Test handling corrupted metadata."""
        run_id = "test-run-corrupt"
        mock_redis.hgetall.return_value = {
            "state": AgentRunState.RUNNING.value,
            "timestamp": "2024-01-01T12:00:00",
            "metadata": "invalid json {{{",
        }

        result = state_tracker.get_run_state(run_id)

        assert result is not None
        assert result["metadata"] == {}

    def test_get_run_state_redis_error(self, state_tracker, mock_redis):
        """Test handling Redis errors."""
        mock_redis.hgetall.side_effect = redis.RedisError("Connection lost")

        result = state_tracker.get_run_state("any-run")

        assert result is None

    def test_clear_run_state_success(self, state_tracker, mock_redis):
        """Test clearing run state from Redis."""
        run_id = "test-run-123"

        result = state_tracker.clear_run_state(run_id)

        assert result is True
        mock_redis.delete.assert_called_once()

    def test_clear_run_state_redis_error(self, state_tracker, mock_redis):
        """Test handling Redis errors when clearing state."""
        mock_redis.delete.side_effect = redis.RedisError("Connection lost")

        result = state_tracker.clear_run_state("any-run")

        assert result is False

    def test_get_all_stuck_runs(self, state_tracker, mock_redis):
        """Test retrieving all stuck runs."""
        mock_redis.keys.return_value = [
            "agent:run:run-1",
            "agent:run:run-2",
            "agent:run:run-3",
        ]

        mock_redis.hgetall.side_effect = [
            {
                "state": AgentRunState.CALL_BACKEND_FAILED.value,
                "timestamp": "2024-01-01T12:00:00",
                "metadata": "{}",
            },
            {
                "state": AgentRunState.AWAITING_APPROVAL.value,
                "timestamp": "2024-01-01T12:00:00",
                "metadata": "{}",
            },
            {
                "state": AgentRunState.COMPLETED.value,
                "timestamp": "2024-01-01T12:00:00",
                "metadata": "{}",
            },
        ]

        result = state_tracker.get_all_stuck_runs()

        assert len(result) == 2
        assert "run-1" in result
        assert "run-2" in result
        assert "run-3" not in result

    def test_get_completed_unacknowledged_runs(self, state_tracker, mock_redis):
        """Test retrieving unacknowledged completed runs."""
        mock_redis.keys.return_value = [
            "agent:run:run-1",  # Completed 10 minutes ago - should be included
            "agent:run:run-2",  # Completed 2 minutes ago - should not be included
        ]

        mock_redis.hgetall.side_effect = [
            {
                "state": AgentRunState.CALL_BACKEND_ACKED.value,
                "timestamp": "2024-01-01T11:50:00",  # 10 minutes ago
                "metadata": "{}",
            },
            {
                "state": AgentRunState.CALL_BACKEND_ACKED.value,
                "timestamp": "2024-01-01T11:58:00",  # 2 minutes ago
                "metadata": "{}",
            },
        ]

        # Patch datetime.utcnow to return consistent time
        with patch('agent_state.datetime') as mock_datetime:
            mock_datetime.utcnow.return_value = datetime(2024, 1, 1, 12:00, 0)
            result = state_tracker.get_completed_unacknowledged_runs()

        assert len(result) == 1
        assert "run-1" in result


class TestAgentRunState:
    """Tests for the AgentRunState enum."""

    def test_all_states_defined(self):
        """Test that all expected states are defined."""
        expected_states = [
            "PENDING",
            "RUNNING",
            "COMPLETED",
            "CALLING_BACKEND",
            "CALL_BACKEND_FAILED",
            "CALL_BACKEND_ACKED",
            "AWAITING_APPROVAL",
            "RETRY_READY",
            "RETRYING",
            "AWAITING_NEXT_TRIGGER",
        ]

        for state_name in expected_states:
            assert hasattr(AgentRunState, state_name)
            state = getattr(AgentRunState, state_name)
            assert isinstance(state, AgentRunState)

    def test_state_value_access(self):
        """Test accessing state values."""
        assert AgentRunState.RUNNING.value == "RUNNING"
        assert AgentRunState.COMPLETED.value == "COMPLETED"


class TestBackendCallbackHandler:
    """Tests for the BackendCallbackHandler class."""

    @pytest.fixture
    def mock_redis(self):
        """Create a mock Redis client."""
        with patch('redis.Redis') as mock_redis_class:
            mock_client = MagicMock(spec=redis.Redis)
            mock_redis_class.return_value = mock_client
            yield mock_client

    @pytest.fixture
    def mock_state_tracker(self, mock_redis):
        """Create a mock state tracker."""
        return AgentState(mock_redis)

    @pytest.fixture
    def callback_handler(self, mock_state_tracker):
        """Create a BackendCallbackHandler instance."""
        return BackendCallbackHandler(
            backend_url="http://test-backend:8080/api/internal/agent",
            state_tracker=mock_state_tracker
        )

    @patch('agent_state.requests.post')
    def test_push_event_success(self, mock_post, callback_handler):
        """Test successful event push."""
        mock_response = Mock()
        mock_response.status_code = 200
        mock_post.return_value = mock_response

        result = callback_handler.push_event(
            run_id="test-run-123",
            event_type="STARTED",
            message="Agent started",
            payload={"agent": "pm"}
        )

        assert result is True
        mock_post.assert_called_once()

    @patch('agent_state.requests.post')
    def test_push_event_failure(self, mock_post, callback_handler):
        """Test event push failure."""
        mock_post.side_effect = requests.exceptions.ConnectionError("No connection")

        result = callback_handler.push_event(
            run_id="test-run-123",
            event_type="STARTED",
            message="Agent started"
        )

        assert result is False

    @patch('agent_state.requests.post')
    @patch('time.sleep')
    def test_push_event_with_retries(self, mock_sleep, mock_post, callback_handler):
        """Test event push with retries."""
        # First two calls fail, third succeeds
        mock_post.side_effect = [
            requests.exceptions.Timeout(),
            requests.exceptions.ConnectionError(),
            Mock(status_code=200),
        ]

        result = callback_handler.push_event(
            run_id="test-run-123",
            event_type="STARTED",
            message="Agent started"
        )

        assert result is True
        assert mock_post.call_count == 3
        assert mock_sleep.call_count == 2

    @patch('agent_state.requests.post')
    def test_complete_run_success(self, mock_post, callback_handler, mock_state_tracker):
        """Test successful completion callback."""
        mock_response = Mock()
        mock_response.status_code = 200
        mock_post.return_value = mock_response

        report = {
            "summary": "Test complete",
            "tokens_used": 100,
            "deliverables": [],
        }

        result = callback_handler.complete_run("test-run-123", report)

        assert result is True
        mock_state_tracker.save_run_state.assert_called()

    @patch('agent_state.requests.post')
    @patch('time.sleep')
    def test_complete_run_with_retries(self, mock_sleep, mock_post, callback_handler):
        """Test completion callback with retries."""
        mock_post.side_effect = [
            requests.exceptions.ConnectionError("No connection"),
            requests.exceptions.ConnectionError("No connection"),
            Mock(status_code=200),
        ]

        report = {
            "summary": "Test complete",
            "tokens_used": 100,
        }

        result = callback_handler.complete_run("test-run-123", report)

        assert result is True
        assert mock_post.call_count == 3

    @patch('agent_state.requests.post')
    @patch('time.sleep')
    def test_complete_run_max_retries_exceeded(self, mock_sleep, mock_post, callback_handler):
        """Test completion callback exhausting all retries."""
        mock_post.side_effect = requests.exceptions.ConnectionError("No connection")

        report = {
            "summary": "Test complete",
            "tokens_used": 100,
        }

        result = callback_handler.complete_run("test-run-123", report)

        assert result is False
        assert mock_post.call_count == MAX_RETRY_ATTEMPTS

    @patch('agent_state.requests.post')
    def test_complete_run_backend_error_400(self, mock_post, callback_handler):
        """Test handling backend 400 error."""
        mock_response = Mock()
        mock_response.status_code = 400
        mock_response.json.return_value = {"message": "Invalid data"}
        mock_post.return_value = mock_response

        report = {
            "summary": "Test complete",
            "tokens_used": 100,
        }

        result = callback_handler.complete_run("test-run-123", report)

        assert result is False

    @patch('agent_state.requests.post')
    def test_complete_run_non_retryable_status(self, mock_post, callback_handler):
        """Test that non-retryable errors are not retried."""
        mock_response = Mock()
        mock_response.status_code = 400
        mock_response.json.return_value = {"message": "Invalid input"}
        mock_post.return_value = mock_response

        report = {"summary": "Test", "tokens_used": 100}
        callback_handler.complete_run("test-run-123", report)

        # Should not retry on 400
        assert mock_post.call_count == 1


class TestSignalConsumer:
    """Tests for the SignalConsumer class."""

    @pytest.fixture
    def mock_redis(self):
        """Create a mock Redis client."""
        with patch('redis.Redis') as mock_redis_class:
            mock_client = MagicMock(spec=redis.Redis)
            mock_redis_class.return_value = mock_client
            yield mock_client

    @pytest.fixture
    def signal_consumer(self, mock_redis):
        """Create a SignalConsumer instance."""
        return SignalConsumer(redis_client=mock_redis)

    def test_start_stops_consumer(self, signal_consumer, mock_redis):
        """Test starting and stopping the signal consumer."""
        # Mock the pubsub object
        mock_pubsub = MagicMock()
        signal_consumer.redis.pubsub.return_value = mock_pubsub

        signal_consumer.start()
        time.sleep(0.1)  # Allow thread to start
        signal_consumer.stop()

        assert signal_consumer._running is False

    @patch('agent_state.json.loads')
    def test_handle_resume_signal(self, mock_json_loads, signal_consumer):
        """Test handling RESUME signal."""
        mock_json_loads.return_value = {
            "signal_type": "RESUME",
            "run_id": "test-run-123",
            "data": {"reason": "Backend restart"},
        }

        signal_consumer._handle_message('{"signal_type": "RESUME"}')

        # Verify state was updated
        signal_consumer.state_tracker.save_run_state.assert_called()

    @patch('agent_state.json.loads')
    def test_handle_approve_signal(self, mock_json_loads, signal_consumer):
        """Test handling APPROVE signal."""
        mock_json_loads.return_value = {
            "signal_type": "APPROVE",
            "run_id": "test-run-123",
        }

        signal_consumer._handle_message('{"signal_type": "APPROVE"}')

    @patch('agent_state.json.loads')
    def test_handle_reject_signal(self, mock_json_loads, signal_consumer):
        """Test handling REJECT signal."""
        mock_json_loads.return_value = {
            "signal_type": "REJECT",
            "run_id": "test-run-123",
            "data": {"reason": "Not approved"},
        }

        signal_consumer._handle_message('{"signal_type": "REJECT"}')

    def test_handle_message_invalid_json(self, signal_consumer):
        """Test handling invalid JSON message."""
        # Should not raise exception
        signal_consumer._handle_message("invalid json {{{")

    def test_on_shutdown(self, signal_consumer):
        """Test clean shutdown."""
        signal_consumer.stop = MagicMock()
        signal_consumer.on_shutdown()

        signal_consumer.stop.assert_called_once()


class TestRecoverStuckRuns:
    """Tests for the recover_stuck_runs function."""

    @pytest.fixture
    def mock_redis(self):
        """Create a mock Redis client."""
        with patch('redis.Redis') as mock_redis_class:
            mock_client = MagicMock(spec=redis.Redis)
            mock_redis_class.return_value = mock_client
            yield mock_client

    @pytest.fixture
    def state_tracker(self, mock_redis):
        """Create an AgentState instance."""
        return AgentState(mock_redis)

    @patch('agent_state.requests.post')
    def test_recover_callback_failed_run(self, mock_post, state_tracker, mock_redis):
        """Test recovery of a run that failed to callback."""
        # Setup mock for stuck run
        mock_redis.keys.return_value = ["agent:run:run-1"]
        mock_redis.hgetall.return_value = {
            "state": AgentRunState.CALL_BACKEND_FAILED.value,
            "timestamp": "2024-01-01T12:00:00",
            "metadata": json.dumps({"attempt": 1}),
        }

        # Mock successful recovery
        mock_post.return_value = Mock(status_code=200)

        with patch.object(state_tracker, 'save_run_state'):
            result = recover_stuck_runs(state_tracker)

        assert isinstance(result, list)

    def test_recover_no_stuck_runs(self, state_tracker, mock_redis):
        """Test recovery with no stuck runs."""
        mock_redis.keys.return_value = []

        result = recover_stuck_runs(state_tracker)

        assert result == []

    @patch('agent_state.AgencyState')
    def test_recover_redis_error(self, mock_agency_state):
        """Test recovery when Redis errors."""
        mock_tracker = MagicMock()
        mock_tracker.get_all_stuck_runs.side_effect = redis.RedisError("Connection")

        with patch('agent_state.AgentState', return_value=mock_tracker):
            result = recover_stuck_runs()

        assert result == []


class TestMetadataHelpers:
    """Tests for metadata helper functions."""

    @pytest.fixture
    def mock_redis(self):
        """Create a mock Redis client."""
        with patch('redis.Redis') as mock_redis_class:
            mock_client = MagicMock(spec=redis.Redis)
            mock_redis_class.return_value = mock_client
            yield mock_client

    def test_persist_run_metadata_success(self, mock_redis):
        """Test persisting run metadata."""
        mock_redis.hgetall.return_value = {
            "state": AgentRunState.RUNNING.value,
            "timestamp": "2024-01-01T12:00:00",
            "metadata": "{}",
        }

        result = persist_run_metadata("run-123", "test_key", "test_value")

        assert result is True

    def test_get_run_metadata_success(self, mock_redis):
        """Test retrieving run metadata."""
        mock_redis.hgetall.return_value = {
            "state": AgentRunState.RUNNING.value,
            "timestamp": "2024-01-01T12:00:00",
            "metadata": json.dumps({"test_key": "test_value"}),
        }

        result = get_run_metadata("run-123")

        assert result is not None
        assert result["test_key"] == "test_value"

    def test_get_run_metadata_not_found(self, mock_redis):
        """Test getting metadata for non-existent run."""
        mock_redis.hgetall.return_value = {}

        result = get_run_metadata("non-existent")

        assert result is None


class TestIntegration:
    """Integration-style tests for complete workflows."""

    @pytest.fixture
    def mock_redis(self):
        """Create a mock Redis client."""
        with patch('redis.Redis') as mock_redis_class:
            mock_client = MagicMock(spec=redis.Redis)
            mock_redis_class.return_value = mock_client
            yield mock_client

    def test_full_workflow(self, mock_redis):
        """Test complete workflow from PENDING to COMPLETED."""
        state_tracker = AgentState(mock_redis)

        run_id = "workflow-test-123"

        # Start a run
        state_tracker.save_run_state(run_id, AgentRunState.RUNNING)
        result = state_tracker.get_run_state(run_id)
        assert result["state"] == AgentRunState.RUNNING

        # Mark as calling backend
        state_tracker.save_run_state(
            run_id,
            AgentRunState.CALLING_BACKEND,
            {"attempt": 0}
        )
        result = state_tracker.get_run_state(run_id)
        assert result["state"] == AgentRunState.CALLING_BACKEND

        # Complete successfully
        state_tracker.save_run_state(
            run_id,
            AgentRunState.CALL_BACKEND_ACKED,
            {"backend_ack": True}
        )
        result = state_tracker.get_run_state(run_id)
        assert result["state"] == AgentRunState.CALL_BACKEND_ACKED
        assert result["metadata"]["backend_ack"] is True

        # Clear state
        result = state_tracker.clear_run_state(run_id)
        assert result is True
        result = state_tracker.get_run_state(run_id)
        assert result is None


# Run tests
if __name__ == "__main__":
    pytest.main([__file__, "-v"])
