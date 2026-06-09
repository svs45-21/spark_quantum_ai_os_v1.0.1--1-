"""
SPARK QUANTUM AI OS — Agent Registry
Central registry for all agents. Handles discovery, routing, and lifecycle.
"""
from __future__ import annotations

import logging
from typing import Dict, List, Optional, Type

from agents.base_agent import BaseAgent, Task, TaskPriority
from core.events.bus import EventBus, get_event_bus

logger = logging.getLogger("spark.registry")


class AgentRegistry:
    """
    Central registry for all SPARK agents.
    Supports: registration, lookup, task routing, status monitoring.
    """

    def __init__(self, bus: Optional[EventBus] = None) -> None:
        self._bus = bus or get_event_bus()
        self._agents: Dict[str, BaseAgent] = {}

    def register(self, agent: BaseAgent) -> None:
        self._agents[agent.name] = agent
        logger.info(f"Registered agent: {agent.name}")

    def unregister(self, name: str) -> None:
        self._agents.pop(name, None)

    def get(self, name: str) -> Optional[BaseAgent]:
        return self._agents.get(name)

    def all(self) -> List[BaseAgent]:
        return list(self._agents.values())

    def names(self) -> List[str]:
        return list(self._agents.keys())

    async def start_all(self) -> None:
        for agent in self._agents.values():
            await agent.start()
        logger.info(f"Started {len(self._agents)} agents")

    async def stop_all(self) -> None:
        for agent in self._agents.values():
            await agent.stop()
        logger.info("All agents stopped")

    async def dispatch(self, agent_name: str, task: Task) -> None:
        agent = self.get(agent_name)
        if not agent:
            raise ValueError(f"Agent not found: {agent_name}")
        await agent.assign_task(task)

    def get_all_statuses(self) -> List[Dict]:
        return [a.get_status_dict() for a in self._agents.values()]

    def __len__(self) -> int:
        return len(self._agents)


_registry: Optional[AgentRegistry] = None


def get_registry() -> AgentRegistry:
    global _registry
    if _registry is None:
        _registry = AgentRegistry()
    return _registry
