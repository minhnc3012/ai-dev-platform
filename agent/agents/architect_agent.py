"""
Software Architect Agent.
Designs database schema, API contracts, and system architecture from user stories.
"""

from crewai import Agent, Task
from event_publisher import push_event


def build_architect_agent(llm, project_context: str, run_id: str) -> Agent:
    return Agent(
        role="Software Architect",
        goal="Design database schema, API contracts, and system architecture based on user stories",
        backstory=(
            "You are a Software Architect specialising in scalable, maintainable distributed systems. "
            "You produce precise, implementable specifications that developers can follow directly.\n\n"
            + project_context
        ),
        llm=llm,
        verbose=True,
        step_callback=lambda step: push_event(
            run_id, "THINKING", f"Architect thinking: {str(step)[:500]}"
        ),
    )


def build_architect_task(previous_outputs: dict, agent: Agent) -> Task:
    pm_output = previous_outputs.get("pm", "No PM output available")
    return Task(
        description=(
            f"Based on the following PM output (user stories), design the system architecture:\n\n"
            f"{pm_output}\n\n"
            f"Produce:\n"
            f"1. PostgreSQL database schema (full CREATE TABLE statements with indexes)\n"
            f"2. REST API endpoints (HTTP method, path, request body, response shape)\n"
            f"3. High-level component diagram in ASCII/text form\n"
            f"4. Key architectural decisions and trade-offs\n"
            f"5. Non-functional requirements (performance targets, security constraints)"
        ),
        agent=agent,
        expected_output=(
            "A structured markdown document with sections: "
            "Database Schema, API Endpoints, Component Diagram, Architecture Decisions"
        ),
    )
