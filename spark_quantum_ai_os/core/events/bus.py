"""
SPARK QUANTUM AI OS — Event Bus
Async pub/sub event system for decoupled agent communication.
"""
from __future__ import annotations

import asyncio
import logging
from collections import defaultdict
from dataclasses import dataclass, field
from datetime import datetime
from enum import Enum, auto
from typing import Any, Callable, Coroutine, Dict, List, Optional
from uuid import uuid4

logger = logging.getLogger("spark.events")


class EventType(str, Enum):
    # System
    SYSTEM_STARTUP = "system.startup"
    SYSTEM_SHUTDOWN = "system.shutdown"
    SYSTEM_ERROR = "system.error"
    SYSTEM_READY = "system.ready"

    # User interaction
    USER_COMMAND = "user.command"
    USER_VOICE_INPUT = "user.voice_input"
    USER_TEXT_INPUT = "user.text_input"
    USER_APPROVAL = "user.approval"
    USER_DENIAL = "user.denial"

    # Agent lifecycle
    AGENT_STARTED = "agent.started"
    AGENT_STOPPED = "agent.stopped"
    AGENT_TASK_ASSIGNED = "agent.task_assigned"
    AGENT_TASK_COMPLETED = "agent.task_completed"
    AGENT_TASK_FAILED = "agent.task_failed"
    AGENT_STATUS_UPDATE = "agent.status_update"
    AGENT_NEEDS_APPROVAL = "agent.needs_approval"

    # Memory
    MEMORY_STORED = "memory.stored"
    MEMORY_RETRIEVED = "memory.retrieved"

    # Voice
    VOICE_WAKE_WORD = "voice.wake_word"
    VOICE_LISTENING = "voice.listening"
    VOICE_RECOGNIZED = "voice.recognized"
    VOICE_SPEAKING = "voice.speaking"
    VOICE_DONE = "voice.done"

    # Vision
    VISION_SCREENSHOT = "vision.screenshot"
    VISION_ANALYZED = "vision.analyzed"
    VISION_FACE_DETECTED = "vision.face_detected"

    # Desktop
    DESKTOP_ACTION = "desktop.action"
    DESKTOP_ACTION_DONE = "desktop.action_done"

    # Document
    DOCUMENT_CREATED = "document.created"
    DOCUMENT_OPENED = "document.opened"

    # Research
    RESEARCH_STARTED = "research.started"
    RESEARCH_COMPLETED = "research.completed"

    # Security
    SECURITY_AUDIT = "security.audit"
    SECURITY_VIOLATION = "security.violation"


@dataclass
class Event:
    type: EventType
    payload: Dict[str, Any] = field(default_factory=dict)
    source: str = "system"
    id: str = field(default_factory=lambda: str(uuid4()))
    timestamp: datetime = field(default_factory=datetime.utcnow)
    correlation_id: Optional[str] = None

    def __repr__(self) -> str:
        return f"Event(type={self.type}, source={self.source}, id={self.id[:8]})"


Handler = Callable[[Event], Coroutine[Any, Any, None]]


class EventBus:
    """
    Async publish-subscribe event bus.
    Supports wildcards: subscribe to 'agent.*' to receive all agent events.
    """

    def __init__(self) -> None:
        self._handlers: Dict[str, List[Handler]] = defaultdict(list)
        self._queue: asyncio.Queue[Event] = asyncio.Queue()
        self._running = False
        self._history: List[Event] = []
        self._max_history = 500

    def subscribe(self, event_type: str | EventType, handler: Handler) -> None:
        key = event_type.value if isinstance(event_type, EventType) else event_type
        self._handlers[key].append(handler)
        logger.debug(f"Subscribed {handler.__qualname__} to {key}")

    def unsubscribe(self, event_type: str | EventType, handler: Handler) -> None:
        key = event_type.value if isinstance(event_type, EventType) else event_type
        self._handlers[key] = [h for h in self._handlers[key] if h is not handler]

    async def publish(self, event: Event) -> None:
        self._history.append(event)
        if len(self._history) > self._max_history:
            self._history.pop(0)
        await self._queue.put(event)
        logger.debug(f"Published {event}")

    async def emit(self, event_type: EventType, payload: dict = None, source: str = "system",
                   correlation_id: str = None) -> Event:
        event = Event(
            type=event_type,
            payload=payload or {},
            source=source,
            correlation_id=correlation_id,
        )
        await self.publish(event)
        return event

    async def _dispatch(self, event: Event) -> None:
        matched: List[Handler] = []

        exact_key = event.type.value if isinstance(event.type, EventType) else event.type
        matched.extend(self._handlers.get(exact_key, []))

        # Wildcard support: 'agent.*'
        prefix = exact_key.split(".")[0] + ".*"
        matched.extend(self._handlers.get(prefix, []))

        # Global wildcard
        matched.extend(self._handlers.get("*", []))

        if not matched:
            return

        tasks = [asyncio.create_task(h(event)) for h in matched]
        results = await asyncio.gather(*tasks, return_exceptions=True)
        for r, h in zip(results, matched):
            if isinstance(r, Exception):
                logger.error(f"Handler {h.__qualname__} raised: {r}", exc_info=r)

    async def run(self) -> None:
        self._running = True
        logger.info("EventBus running")
        while self._running:
            try:
                event = await asyncio.wait_for(self._queue.get(), timeout=1.0)
                await self._dispatch(event)
                self._queue.task_done()
            except asyncio.TimeoutError:
                continue
            except Exception as e:
                logger.error(f"EventBus error: {e}", exc_info=True)

    def stop(self) -> None:
        self._running = False

    def get_history(self, event_type: Optional[EventType] = None, limit: int = 50) -> List[Event]:
        history = self._history
        if event_type:
            history = [e for e in history if e.type == event_type]
        return history[-limit:]


# Global singleton
_bus: Optional[EventBus] = None


def get_event_bus() -> EventBus:
    global _bus
    if _bus is None:
        _bus = EventBus()
    return _bus
