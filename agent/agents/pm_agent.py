"""
Product Manager Agent.
Analyses raw customer requirements and produces user stories with acceptance criteria.
"""

from crewai import Agent, Task
from event_publisher import push_event


def build_pm_agent(llm, project_context: str, run_id: str) -> Agent:
    """
    Build the PM agent with the given LLM and project context.

    Args:
        llm:             LLM instance (API, CLI, or SDK mode).
        project_context: Tech stack, coding style, and prior agent outputs.
        run_id:          UUID string for event publishing.
    """
    return Agent(
        role="Product Manager",
        goal="Analyse raw customer requirements and produce clear user stories with acceptance criteria",
        backstory=(
            "You are a senior Product Manager with 10 years of experience translating "
            "vague customer needs into actionable development tasks. You excel at identifying "
            "ambiguities and asking the right clarifying questions.\n\n"
            + project_context
        ),
        llm=llm,
        verbose=True,
        step_callback=lambda step: push_event(
            run_id, "THINKING", f"PM thinking: {str(step)[:500]}"
        ),
    )


def build_pm_task(raw_requirement: str, agent: Agent) -> Task:
    """Build the PM analysis task for a given raw requirement."""
    return Task(
        description=(
            f"Analyse the following raw customer requirement and produce:\n\n"
            f"1. A numbered list of user stories in the format:\n"
            f"   'As a <role>, I want <feature>, so that <benefit>'\n\n"
            f"2. Acceptance criteria for each user story (bullet points).\n\n"
            f"3. A list of open questions or ambiguities that need owner clarification "
            f"   (mark as BLOCKING if they prevent implementation).\n\n"
            f"4. A confidence score (0-100) indicating how well-defined the requirement is, "
            f"   with a brief explanation.\n\n"
            f"RAW REQUIREMENT:\n{raw_requirement}"
        ),
        agent=agent,
        expected_output=(
            "A structured markdown document with sections: "
            "User Stories, Acceptance Criteria, Open Questions, Confidence Score"
        ),
    )
