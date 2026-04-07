"""
QA Engineer Agent.
Reviews implementation for bugs, edge cases, and security vulnerabilities.
"""

from crewai import Agent, Task
from event_publisher import push_event


def build_qa_agent(llm, project_context: str, run_id: str) -> Agent:
    return Agent(
        role="QA Engineer",
        goal="Review the implementation for bugs, missing edge cases, and security vulnerabilities",
        backstory=(
            "You are a QA Engineer focused on test automation and security. "
            "You are thorough in identifying edge cases and security risks following OWASP guidelines.\n\n"
            + project_context
        ),
        llm=llm,
        verbose=True,
        step_callback=lambda step: push_event(
            run_id, "THINKING", f"QA thinking: {str(step)[:500]}"
        ),
    )


def build_qa_task(previous_outputs: dict, agent: Agent) -> Task:
    dev_output = previous_outputs.get("dev", "No developer output available")
    return Task(
        description=(
            f"Review the following implementation and produce a QA report:\n\n"
            f"{dev_output}\n\n"
            f"Produce:\n"
            f"1. List of bugs found (severity: CRITICAL / MAJOR / MINOR) with line references\n"
            f"2. Missing edge cases or boundary conditions not covered by tests\n"
            f"3. Security review against OWASP Top 10 (note which items are applicable)\n"
            f"4. Performance concerns if any\n"
            f"5. Overall quality assessment (PASS / PASS_WITH_ISSUES / FAIL)\n"
            f"6. Recommended test scenarios that must be added before production release"
        ),
        agent=agent,
        expected_output=(
            "A structured QA report with sections: "
            "Bug List, Edge Cases, Security Review, Quality Assessment, Test Recommendations"
        ),
    )
