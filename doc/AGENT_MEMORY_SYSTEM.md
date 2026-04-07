# Agent Memory & Session Persistence System

## Overview

This document describes the **3-tier memory system** for agent workflows, which solves the challenge of maintaining context across agent sessions, handle app restarts, and prevent duplicate agent runs.

## Problem Statement

When building agentic systems, a key challenge is **memory persistence**:
- When the app restarts or crashes, agent state is lost
- Multiple agents can be created for the same task (duplicates)
- Users can't resume work after stopping the pipeline
- Context is lost between agent handoffs

## Solution: 3-Tier Memory Architecture

The system uses three tiers of memory, each with increasing specificity and persistence:

### Tier 1: Structured Facts (Most Persistent)
**Storage:** PostgreSQL (`agent_reports` table)  
**Purpose:** Never-forget data - what was decided, created, or delivered

Includes:
- **Deliverables:** Files created, code written, schemas defined
- **Issues Found:** Problems encountered, bugs discovered
- **Owner Decisions:** Decisions made with trade-offs

**Why it matters:** The next agent needs to know what already exists and what decisions were made.

### Tier 2: Session Summary (Persistent)
**Storage:** PostgreSQL (`agent_sessions` table)  
**Purpose:** LLM-compressed summary of work accomplished + reasoning

Includes:
- **Summary:** What the agent accomplished
- **Reasoning:** Why certain decisions were made (design choices, trade-offs)

**Why it matters:** Agents need to understand the "why" behind decisions, not just the "what". This captures design intent.

### Tier 3: Recent Messages (Ephemeral)
**Storage:** Redis with TTL (24 hours)  
**Purpose:** Current conversation context - what's happening right now

Includes:
- **Last N messages:** Recent chat history (up to 20 messages)

**Why it matters:** When an agent resumes, it needs to know where it left off (e.g., "debugging authentication error").

## System Flow

```
Agent completes work
    ↓
handleAgentComplete() → save AgentReport (Tier 1)
    ↓
compressSessionFromReport() → extract structured facts
    ↓
save AgentSession (Tier 2)
    ↓
save recent messages to Redis (Tier 3, TTL 24h)


Next agent needs context
    ↓
buildFullTaskConfig()
    ↓
buildAgentMemoryContext() → gather 3-tier memory
    ↓
inject into task config sent to Python agent
```

## Code Components

### Core Files

| File | Purpose |
|------|---------|
| `AgentSession.java` | Entity storing session metadata |
| `AgentSessionRepository.java` | Database query methods |
| `SessionCompressorService.java` | Compress sessions from AgentReport |
| `SessionCleanupService.java` | Scheduled cleanup of expired Redis keys |
| `ChatSummarizationService.java` | LLM-based chat summarization |
| `AgentOrchestrator.java` | Core pipeline orchestrator |

### Key Methods

**`SessionCompressorService.compressSessionFromReport(runId)`**
- Extract structured facts from AgentReport
- Create AgentSession record
- Persist to database

**`SessionCompressorService.getSessionContext(runId)`**
- Retrieve session context for a run
- Returns: structuredFacts, sessionSummary, reasoningSummary, recentRawMessages

**`AgentOrchestrator.buildFullTaskConfig(run)`**
- Builds task configuration with 3-tier memory
- Injects `systemPrompt` and `sessionMemory` for Python agent

**`AgentOrchestrator.buildSystemPrompt(run, context)`**
- Formats context as human-readable system prompt
- Sends to Python agent as part of task config

## Python Agent Integration

When the Python agent receives a task, it includes:

```json
{
  "run_id": "...",
  "previousOutputs": {
    "pm": "PM Agent completed: ...",
    "architect": "Architect completed: ..."
  },
  "systemPrompt": "## Agent Context\n\n## Session Summary\n[summary]\n\n## Design Decisions\n[reasoning]",
  "sessionMemory": {
    "structuredFacts": {...},
    "sessionSummary": "...",
    "reasoningSummary": "..."
  }
}
```

The Python agent prepends `systemPrompt` to its LLM context, giving it full awareness of previous work.

## Configuration

Add to `application.yml`:

```yaml
ai:
  summarization:
    model: "gpt-4o"              # LLM model for summarization
    api-key: "${SUMMARIZATION_API_KEY:}"
    base-url: "${SUMMARIZATION_BASE_URL:}"
```

## Scheduled Tasks

| Task | Schedule | Purpose |
|------|----------|---------|
| `cleanupExpiredSessionMessages()` | Daily 2 AM | Remove expired Redis session keys |
| `archiveOldSessions()` | Weekly Sunday 3 AM | Archive sessions older than 7 days |
| `deleteArchivedSessions()` | Weekly Sunday 4 AM | Delete archived sessions older than 30 days |

## Example System Prompt Generated

```
## Agent Context
Current Agent: dev

## Completed Work (Structured Facts)
{
  "pm_deliverables": [{"name": "requirements", "type": "doc"}],
  "architect_deliverables": [{"name": "design", "type": "doc"}]
}

## Session Summary
PM Agent completed: Created project scope and requirements
Architect completed: Designed high-level system architecture

## Design Decisions & Trade-offs
Key design decisions:
- REST API with JSON payloads
- JWT authentication with 24h token expiry
- Modular architecture for extensibility

## Recent Activity
Last user message: "Please implement authentication"
```

## Testing

Run the integration test:

```bash
mvn test -Dtest=SessionMemoryIntegrationTest
```

Tests cover:
- Session compression from AgentReport
- Context retrieval with/without prior sessions
- Accumulated memory from multiple agents
- Redis cache integration
- Chat summarization

## Benefits

1. **No Duplicate Agents:** Unique constraint on (module_id, agent_name) prevents duplicates
2. **Resumable Workflows:** Can stop and resume pipeline without losing context
3. **Persistent Memory:** Structured facts survive app restarts
4. **Informed Agents:** Each agent knows what previous agents did and why
5. **Scalable:** Redis TTL handles ephemeral data automatically
6. **Clean State:** Scheduled cleanup prevents data bloat

## Future Enhancements

1. **Dynamic Summarization:** Call LLM to generate better summaries from raw chat history
2. **Decision Extraction:** Automatically detect and extract decision patterns from conversations
3. **Cross-Session Learning:** Agents can learn from patterns across multiple module runs
4. **Memory Trimming:** Automatically compress session summary when it gets too long
5. **Hybrid Search:** Searchable memory store for quick retrieval of relevant past decisions

## Related Files

- `backend/src/main/java/com/aidevplatform/domain/entity/AgentSession.java`
- `backend/src/main/java/com/aidevplatform/service/SessionCompressorService.java`
- `backend/src/main/java/com/aidevplatform/service/AgentOrchestrator.java`
- `backend/src/main/java/com/aidevplatform/service/SessionCleanupService.java`
- `backend/src/main/java/com/aidevplatform/service/ChatSummarizationService.java`
