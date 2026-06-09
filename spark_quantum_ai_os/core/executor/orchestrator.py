"""
SPARK QUANTUM AI OS — Multi-Agent Orchestrator
Phase 9: Coordinates multiple agents to complete complex multi-step tasks.
Handles dependencies, parallel execution, error recovery.
"""
from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass, field
from datetime import datetime
from typing import Any, Dict, List, Optional
from uuid import uuid4

from agents.base_agent import Task, TaskPriority
from core.events.bus import EventBus, EventType, get_event_bus
from core.registry.agent_registry import AgentRegistry, get_registry

logger = logging.getLogger("spark.orchestrator")


@dataclass
class WorkflowStep:
    id: str = field(default_factory=lambda: str(uuid4()))
    agent: str = ""
    task_name: str = ""
    description: str = ""
    payload: Dict[str, Any] = field(default_factory=dict)
    requires_approval: bool = False
    depends_on: List[str] = field(default_factory=list)
    priority: TaskPriority = TaskPriority.NORMAL
    result: Optional[Any] = None
    error: Optional[str] = None
    status: str = "pending"  # pending | running | completed | failed | skipped


@dataclass
class Workflow:
    id: str = field(default_factory=lambda: str(uuid4()))
    name: str = ""
    description: str = ""
    steps: List[WorkflowStep] = field(default_factory=list)
    created_at: datetime = field(default_factory=datetime.utcnow)
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    status: str = "pending"
    results: Dict[str, Any] = field(default_factory=dict)


class Orchestrator:
    """
    Multi-agent orchestrator.

    Features:
    - Dependency resolution (DAG-based)
    - Parallel execution of independent steps
    - Error recovery (continue on non-critical failure)
    - Context passing between steps
    - Progress reporting via event bus
    """

    def __init__(
        self,
        registry: Optional[AgentRegistry] = None,
        bus: Optional[EventBus] = None,
    ) -> None:
        self._registry = registry or get_registry()
        self._bus = bus or get_event_bus()
        self._active_workflows: Dict[str, Workflow] = {}

    async def run_workflow(self, workflow: Workflow) -> Dict[str, Any]:
        """Execute a complete workflow, respecting step dependencies."""
        self._active_workflows[workflow.id] = workflow
        workflow.status = "running"
        workflow.started_at = datetime.utcnow()

        await self._bus.emit(
            EventType.AGENT_TASK_ASSIGNED,
            {
                "workflow_id": workflow.id,
                "workflow_name": workflow.name,
                "total_steps": len(workflow.steps),
            },
            source="orchestrator",
        )

        logger.info(f"Workflow started: {workflow.name} [{workflow.id[:8]}] — {len(workflow.steps)} steps")

        context: Dict[str, Any] = {}  # Shared results between steps

        try:
            await self._execute_dag(workflow, context)
            workflow.status = "completed"
        except Exception as e:
            workflow.status = "failed"
            logger.error(f"Workflow failed: {workflow.name} — {e}", exc_info=True)
        finally:
            workflow.completed_at = datetime.utcnow()
            duration = (workflow.completed_at - workflow.started_at).total_seconds()

            await self._bus.emit(
                EventType.AGENT_TASK_COMPLETED,
                {
                    "workflow_id": workflow.id,
                    "workflow_name": workflow.name,
                    "status": workflow.status,
                    "duration_seconds": duration,
                    "results": {s.id: s.result for s in workflow.steps},
                },
                source="orchestrator",
            )

            logger.info(f"Workflow {workflow.status}: {workflow.name} in {duration:.1f}s")

        workflow.results = {s.id: s.result for s in workflow.steps}
        return workflow.results

    async def _execute_dag(self, workflow: Workflow, context: Dict) -> None:
        """Execute steps as a DAG — parallel where possible, serial where dependencies require."""
        completed_ids = set()
        failed_ids = set()

        while True:
            # Find steps ready to run
            ready = [
                step for step in workflow.steps
                if step.status == "pending"
                and all(dep in completed_ids for dep in step.depends_on)
                and not any(dep in failed_ids for dep in step.depends_on)
            ]

            if not ready:
                # Check if we're done
                pending = [s for s in workflow.steps if s.status == "pending"]
                if not pending:
                    break
                # All remaining steps blocked by failures
                for step in pending:
                    step.status = "skipped"
                    logger.warning(f"Step skipped (dependency failed): {step.task_name}")
                break

            # Execute ready steps in parallel
            tasks = [self._execute_step(step, context) for step in ready]
            await asyncio.gather(*tasks, return_exceptions=True)

            # Update completed/failed
            for step in ready:
                if step.status == "completed":
                    completed_ids.add(step.id)
                    context[step.id] = step.result
                    # Also add by name for convenience
                    context[step.task_name] = step.result
                elif step.status == "failed":
                    failed_ids.add(step.id)

    async def _execute_step(self, step: WorkflowStep, context: Dict) -> None:
        step.status = "running"

        # Inject context into payload
        enriched_payload = {**step.payload, "_context": context}

        task = Task(
            name=step.task_name,
            description=step.description,
            payload=enriched_payload,
            priority=step.priority,
            requires_approval=step.requires_approval,
        )

        await self._bus.emit(
            EventType.AGENT_STATUS_UPDATE,
            {"step": step.task_name, "agent": step.agent, "status": "running"},
            source="orchestrator",
        )

        try:
            agent = self._registry.get(step.agent)
            if not agent:
                raise ValueError(f"Agent not found: {step.agent}")

            await agent.assign_task(task)

            # Wait for task to complete (poll)
            timeout = 300  # 5 minutes per step
            elapsed = 0
            while elapsed < timeout:
                await asyncio.sleep(0.5)
                elapsed += 0.5

                # Check if task completed by inspecting agent history
                for hist_task in reversed(agent._task_history):
                    if hist_task.id == task.id:
                        if hist_task.error:
                            raise RuntimeError(hist_task.error)
                        step.result = hist_task.result
                        step.status = "completed"
                        logger.info(f"Step completed: {step.task_name}")
                        return

            raise TimeoutError(f"Step timed out after {timeout}s: {step.task_name}")

        except Exception as e:
            step.status = "failed"
            step.error = str(e)
            logger.error(f"Step failed: {step.task_name} — {e}")
            await self._bus.emit(
                EventType.AGENT_TASK_FAILED,
                {"step": step.task_name, "error": str(e)},
                source="orchestrator",
            )

    def build_research_workflow(self, topic: str, output_format: str = "docx") -> Workflow:
        """Pre-built workflow: Research topic → generate documents."""
        steps = [
            WorkflowStep(
                id="search",
                agent="research_agent",
                task_name="search_papers",
                description=f"Search academic papers on: {topic}",
                payload={"action": "search_papers", "params": {"query": topic, "limit": 15}},
            ),
            WorkflowStep(
                id="analyze",
                agent="research_agent",
                task_name="analyze_topic",
                description=f"Analyze research topic: {topic}",
                payload={"action": "analyze_topic", "params": {"topic": topic}},
                depends_on=["search"],
            ),
            WorkflowStep(
                id="citations",
                agent="research_agent",
                task_name="generate_citations",
                description="Generate APA citations",
                payload={"action": "generate_citations", "params": {"style": "apa"}},
                depends_on=["search"],
            ),
            WorkflowStep(
                id="document",
                agent="document_agent",
                task_name="create_literature_review",
                description=f"Create literature review document for: {topic}",
                payload={
                    "action": "create_literature_review",
                    "params": {"topic": topic, "format": output_format},
                },
                depends_on=["analyze", "citations"],
            ),
        ]

        return Workflow(
            name=f"Research: {topic}",
            description=f"Complete research workflow for: {topic}",
            steps=steps,
        )

    def build_code_workflow(self, description: str, language: str = "python") -> Workflow:
        """Pre-built workflow: Generate code → test → docs."""
        steps = [
            WorkflowStep(
                id="generate",
                agent="coding_agent",
                task_name="generate_code",
                description=f"Generate {language} code: {description}",
                payload={"action": "generate_code", "params": {"description": description, "language": language}},
            ),
            WorkflowStep(
                id="review",
                agent="coding_agent",
                task_name="review_code",
                description="Review generated code",
                payload={"action": "review_code", "params": {"language": language}},
                depends_on=["generate"],
            ),
            WorkflowStep(
                id="tests",
                agent="coding_agent",
                task_name="add_tests",
                description="Generate unit tests",
                payload={"action": "add_tests", "params": {"language": language}},
                depends_on=["generate"],
            ),
            WorkflowStep(
                id="docs",
                agent="coding_agent",
                task_name="generate_docs",
                description="Generate documentation",
                payload={"action": "generate_docs", "params": {"language": language}},
                depends_on=["generate"],
            ),
        ]

        return Workflow(
            name=f"Code: {description[:50]}",
            description=f"Full code generation workflow: {description}",
            steps=steps,
        )

    def get_active_workflows(self) -> List[Dict]:
        return [
            {
                "id": w.id,
                "name": w.name,
                "status": w.status,
                "steps": len(w.steps),
                "completed_steps": sum(1 for s in w.steps if s.status == "completed"),
            }
            for w in self._active_workflows.values()
        ]


_orchestrator: Optional[Orchestrator] = None


def get_orchestrator() -> Orchestrator:
    global _orchestrator
    if _orchestrator is None:
        _orchestrator = Orchestrator()
    return _orchestrator
