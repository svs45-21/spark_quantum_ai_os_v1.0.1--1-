"""
SPARK QUANTUM AI OS — Base Agent
All agents inherit from this class. Provides lifecycle, task handling,
status reporting, and event integration.
"""
from __future__ import annotations

import asyncio
import logging
from abc import ABC, abstractmethod
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum
from typing import Any, Dict, List, Optional
from uuid import uuid4

from core.events.bus import Event, EventBus, EventType, get_event_bus

logger = logging.getLogger("spark.agents")


class AgentStatus(str, Enum):
    IDLE = "idle"
    INITIALIZING = "initializing"
    RUNNING = "running"
    WAITING_APPROVAL = "waiting_approval"
    PAUSED = "paused"
    ERROR = "error"
    STOPPED = "stopped"


class TaskPriority(int, Enum):
    LOW = 1
    NORMAL = 5
    HIGH = 8
    CRITICAL = 10


@dataclass
class Task:
    id: str = field(default_factory=lambda: str(uuid4()))
    name: str = ""
    description: str = ""
    payload: Dict[str, Any] = field(default_factory=dict)
    priority: TaskPriority = TaskPriority.NORMAL
    assigned_agent: str = ""
    requires_approval: bool = False
    created_at: datetime = field(default_factory=datetime.utcnow)
    started_at: Optional[datetime] = None
    completed_at: Optional[datetime] = None
    result: Optional[Any] = None
    error: Optional[str] = None
    correlation_id: Optional[str] = None

    @property
    def duration_seconds(self) -> Optional[float]:
        if self.started_at and self.completed_at:
            return (self.completed_at - self.started_at).total_seconds()
        return None


@dataclass
class AgentCapability:
    name: str
    description: str
    requires_approval: bool = False


class BaseAgent(ABC):
    """
    Base class for all SPARK agents.
    Provides: lifecycle management, task queue, event integration,
    status reporting, approval workflow.
    """

    name: str = "base_agent"
    description: str = "Base agent"
    capabilities: List[AgentCapability] = []

    def __init__(self, bus: Optional[EventBus] = None) -> None:
        self._bus = bus or get_event_bus()
        self._status = AgentStatus.IDLE
        self._task_queue: asyncio.Queue[Task] = asyncio.Queue()
        self._current_task: Optional[Task] = None
        self._task_history: List[Task] = []
        self._approval_event: Optional[asyncio.Event] = None
        self._approval_granted: bool = False
        self._logger = logging.getLogger(f"spark.agents.{self.name}")
        self._running = False

        # Subscribe to approval events
        self._bus.subscribe(EventType.USER_APPROVAL, self._on_approval)
        self._bus.subscribe(EventType.USER_DENIAL, self._on_denial)

    # ── Properties ────────────────────────────────────────────────────────────

    @property
    def status(self) -> AgentStatus:
        return self._status

    @property
    def current_task(self) -> Optional[Task]:
        return self._current_task

    # ── Lifecycle ─────────────────────────────────────────────────────────────

    async def start(self) -> None:
        self._status = AgentStatus.INITIALIZING
        self._logger.info(f"Starting {self.name}")
        await self.initialize()
        self._running = True
        self._status = AgentStatus.IDLE
        await self._bus.emit(EventType.AGENT_STARTED, {"agent": self.name}, source=self.name)
        asyncio.create_task(self._run_loop())

    async def stop(self) -> None:
        self._running = False
        self._status = AgentStatus.STOPPED
        await self.cleanup()
        await self._bus.emit(EventType.AGENT_STOPPED, {"agent": self.name}, source=self.name)
        self._logger.info(f"Stopped {self.name}")

    async def initialize(self) -> None:
        """Override to add custom init logic."""
        pass

    async def cleanup(self) -> None:
        """Override to add custom cleanup logic."""
        pass

    # ── Task Management ───────────────────────────────────────────────────────

    async def assign_task(self, task: Task) -> None:
        task.assigned_agent = self.name
        await self._task_queue.put(task)
        await self._bus.emit(
            EventType.AGENT_TASK_ASSIGNED,
            {"agent": self.name, "task_id": task.id, "task_name": task.name},
            source=self.name,
        )
        self._logger.info(f"Task assigned: {task.name} [{task.id[:8]}]")

    async def _run_loop(self) -> None:
        while self._running:
            try:
                task = await asyncio.wait_for(self._task_queue.get(), timeout=1.0)
                await self._execute_task(task)
            except asyncio.TimeoutError:
                continue
            except Exception as e:
                self._logger.error(f"Run loop error: {e}", exc_info=True)

    async def _execute_task(self, task: Task) -> None:
        self._current_task = task
        self._status = AgentStatus.RUNNING
        task.started_at = datetime.utcnow()
        self._logger.info(f"Executing task: {task.name}")

        try:
            if task.requires_approval:
                approved = await self._request_approval(task)
                if not approved:
                    task.error = "Denied by user"
                    await self._bus.emit(EventType.AGENT_TASK_FAILED,
                                         {"agent": self.name, "task_id": task.id, "reason": "denied"},
                                         source=self.name)
                    return

            result = await self.execute(task)
            task.result = result
            task.completed_at = datetime.utcnow()
            self._task_history.append(task)

            await self._bus.emit(
                EventType.AGENT_TASK_COMPLETED,
                {"agent": self.name, "task_id": task.id, "duration": task.duration_seconds},
                source=self.name,
            )
            self._logger.info(f"Task completed: {task.name} in {task.duration_seconds:.2f}s")

        except Exception as e:
            task.error = str(e)
            task.completed_at = datetime.utcnow()
            self._task_history.append(task)
            self._status = AgentStatus.ERROR
            await self._bus.emit(
                EventType.AGENT_TASK_FAILED,
                {"agent": self.name, "task_id": task.id, "error": str(e)},
                source=self.name,
            )
            self._logger.error(f"Task failed: {task.name} — {e}", exc_info=True)

        finally:
            self._current_task = None
            if self._status != AgentStatus.STOPPED:
                self._status = AgentStatus.IDLE

    @abstractmethod
    async def execute(self, task: Task) -> Any:
        """Implement task execution logic in subclasses."""
        ...

    # ── Approval Workflow ─────────────────────────────────────────────────────

    async def _request_approval(self, task: Task) -> bool:
        self._status = AgentStatus.WAITING_APPROVAL
        self._approval_event = asyncio.Event()
        self._approval_granted = False

        await self._bus.emit(
            EventType.AGENT_NEEDS_APPROVAL,
            {
                "agent": self.name,
                "task_id": task.id,
                "task_name": task.name,
                "description": task.description,
            },
            source=self.name,
            correlation_id=task.id,
        )

        self._logger.info(f"Waiting for approval: {task.name}")
        try:
            await asyncio.wait_for(self._approval_event.wait(), timeout=60.0)
        except asyncio.TimeoutError:
            self._logger.warning(f"Approval timed out for {task.name}")
            return False

        self._status = AgentStatus.RUNNING
        return self._approval_granted

    async def _on_approval(self, event: Event) -> None:
        if self._approval_event and not self._approval_event.is_set():
            if event.payload.get("correlation_id") == (
                self._current_task.id if self._current_task else None
            ):
                self._approval_granted = True
                self._approval_event.set()

    async def _on_denial(self, event: Event) -> None:
        if self._approval_event and not self._approval_event.is_set():
            self._approval_granted = False
            self._approval_event.set()

    # ── Status ────────────────────────────────────────────────────────────────

    def get_status_dict(self) -> Dict[str, Any]:
        return {
            "name": self.name,
            "status": self._status.value,
            "current_task": self._current_task.name if self._current_task else None,
            "tasks_completed": len(self._task_history),
            "queue_size": self._task_queue.qsize(),
        }
