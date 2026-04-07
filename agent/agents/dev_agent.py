"""
Senior Developer Agent.
Implements production-ready code following the architecture spec.
"""

from crewai import Agent, Task
from event_publisher import push_event


def build_dev_agent(llm, project_context: str, run_id: str) -> Agent:
    return Agent(
        role="Senior Developer",
        goal="Implement production-ready code according to the architecture spec, including unit tests",
        backstory=(
            "You are a full-stack Senior Developer who writes clean, well-tested, and secure code. "
            "You follow SOLID principles and always include unit tests for business logic.\n\n"
            + project_context
        ),
        llm=llm,
        verbose=True,
        step_callback=lambda step: push_event(
            run_id, "THINKING", f"Dev thinking: {str(step)[:500]}"
        ),
    )


def build_dev_task(previous_outputs: dict, agent: Agent) -> Task:
    architect_output = previous_outputs.get("architect", "No architect output available")
    return Task(
        description=(
            f"Implement the feature based on this architecture specification:\n\n"
            f"{architect_output}\n\n"
            f"Produce:\n"
            f"1. All required source files with complete, working implementation\n"
            f"2. Unit tests for all core business logic functions\n"
            f"3. Integration test stubs where applicable\n"
            f"4. A summary of implementation choices, deviations from spec, and known limitations\n\n"
            f"Requirements:\n"
            f"- All code and comments must be in English\n"
            f"- Follow the project coding style guide\n"
            f"- Do not include TODO comments -- implement fully or document why it is deferred"
        ),
        agent=agent,
        expected_output=(
            "A structured markdown document listing all files created, "
            "with full code content and test coverage summary"
        ),
    )
