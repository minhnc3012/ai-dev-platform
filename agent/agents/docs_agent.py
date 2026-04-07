"""
Technical Writer (Docs) Agent.
Produces comprehensive documentation for all delivered artifacts.
"""

from crewai import Agent, Task
from event_publisher import push_event


def build_docs_agent(llm, project_context: str, run_id: str) -> Agent:
    return Agent(
        role="Technical Writer",
        goal="Write comprehensive technical documentation for all delivered artifacts",
        backstory=(
            "You are a Technical Writer who produces clear, accurate, and developer-friendly "
            "API docs, README files, and changelogs. Your documentation makes onboarding easy.\n\n"
            + project_context
        ),
        llm=llm,
        verbose=True,
        step_callback=lambda step: push_event(
            run_id, "THINKING", f"Docs thinking: {str(step)[:500]}"
        ),
    )


def build_docs_task(previous_outputs: dict, agent: Agent) -> Task:
    all_outputs = "\n\n---\n\n".join(
        f"## {agent_name.upper()} OUTPUT\n{output}"
        for agent_name, output in previous_outputs.items()
    )
    return Task(
        description=(
            f"Write technical documentation based on all agent outputs:\n\n"
            f"{all_outputs}\n\n"
            f"Produce:\n"
            f"1. README.md with: project overview, prerequisites, setup instructions, usage examples\n"
            f"2. API documentation: all endpoints with curl examples and response samples\n"
            f"3. Database schema documentation with entity relationship description\n"
            f"4. CHANGELOG.md entry for this module (follow Keep a Changelog format)\n"
            f"5. Deployment notes (environment variables, dependencies, migration steps)"
        ),
        agent=agent,
        expected_output=(
            "A set of markdown documents: README.md, API_DOCS.md, SCHEMA_DOCS.md, CHANGELOG.md"
        ),
    )
