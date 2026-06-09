"""
SPARK QUANTUM AI OS — Executive Agent
The top-level orchestrator. Understands user intent, plans execution,
delegates to specialized agents, and reports back.
"""
from __future__ import annotations

import json
import logging
from typing import Any, Optional

from agents.base_agent import AgentCapability, BaseAgent, Task, TaskPriority
from core.events.bus import Event, EventBus, EventType
from core.router.model_router import ModelRouter, TaskType as RouterTaskType
from core.settings import get_settings

logger = logging.getLogger("spark.executive")

EXECUTIVE_SYSTEM_PROMPT = """
You are SPARK, an elite AI Operating System executive agent.
Your job is to understand user intent and create a precise execution plan.

User Profile:
- Name: Mr. Shree Ranjan
- Nickname: Vayala

Available agents:
- research_agent: academic research, papers, literature reviews
- document_agent: create DOCX, PDF, PPTX, Excel files
- coding_agent: write, debug, review code
- desktop_agent: control computer, open apps, manage files
- browser_agent: browse internet, search, fill forms
- memory_agent: store and retrieve information
- vision_agent: screenshot analysis, OCR, screen reading
- whatsapp_agent: WhatsApp messages and groups

Respond ONLY with valid JSON in this exact format:
{
  "intent": "brief description of what user wants",
  "plan": [
    {
      "step": 1,
      "agent": "agent_name",
      "task": "task_name",
      "description": "what to do",
      "requires_approval": false,
      "depends_on": []
    }
  ],
  "response_to_user": "What to tell the user before starting",
  "priority": "normal"
}
"""


class ExecutiveAgent(BaseAgent):
    name = "executive_agent"
    description = "Top-level orchestrator. Plans and coordinates all SPARK operations."
    capabilities = [
        AgentCapability("plan", "Understand intent and create execution plan"),
        AgentCapability("delegate", "Assign tasks to specialized agents"),
        AgentCapability("report", "Report progress and results to user"),
    ]

    def __init__(self, router: Optional[ModelRouter] = None, bus: Optional[EventBus] = None) -> None:
        super().__init__(bus)
        self._router = router or ModelRouter()
        self._settings = get_settings()

        # Subscribe to user input
        self._bus.subscribe(EventType.USER_COMMAND, self._on_user_command)
        self._bus.subscribe(EventType.USER_VOICE_INPUT, self._on_user_command)

    async def initialize(self) -> None:
        logger.info("Executive Agent initialized")
        await self._bus.emit(
            EventType.AGENT_STATUS_UPDATE,
            {"agent": self.name, "message": "Executive Agent ready"},
            source=self.name,
        )

    async def execute(self, task: Task) -> Any:
        """Process a user command and produce an execution plan."""
        user_input = task.payload.get("text", "")
        plan = await self._plan(user_input)
        await self._announce_plan(plan)
        return plan

    async def _on_user_command(self, event: Event) -> None:
        text = event.payload.get("text", "")
        if not text:
            return

        logger.info(f"Executive received command: {text[:80]}")

        task = Task(
            name="process_command",
            description=f"Process user command: {text[:80]}",
            payload={"text": text},
            priority=TaskPriority.HIGH,
            correlation_id=event.id,
        )
        await self.assign_task(task)

    async def _plan(self, user_input: str) -> dict:
        """Ask the LLM to produce an execution plan."""
        try:
            response = await self._router.complete(
                messages=[{"role": "user", "content": user_input}],
                task_type=RouterTaskType.REASONING,
                system=EXECUTIVE_SYSTEM_PROMPT,
                max_tokens=2000,
                temperature=0.2,
            )

            # Strip markdown fences if present
            clean = response.strip()
            if clean.startswith("```"):
                clean = clean.split("```")[1]
                if clean.startswith("json"):
                    clean = clean[4:]
            clean = clean.strip()

            plan = json.loads(clean)
            return plan

        except json.JSONDecodeError as e:
            logger.error(f"Failed to parse plan JSON: {e}")
            return {
                "intent": user_input,
                "plan": [],
                "response_to_user": "I understood your request but had trouble planning it. Please try rephrasing.",
                "priority": "normal",
            }
        except Exception as e:
            logger.error(f"Planning failed: {e}", exc_info=True)
            return {
                "intent": user_input,
                "plan": [],
                "response_to_user": f"An error occurred while planning: {e}",
                "priority": "normal",
            }

    async def _announce_plan(self, plan: dict) -> None:
        """Broadcast plan to UI and voice."""
        response_text = plan.get("response_to_user", "Processing your request...")
        await self._bus.emit(
            EventType.AGENT_STATUS_UPDATE,
            {
                "agent": self.name,
                "message": response_text,
                "plan": plan,
                "speak": True,
            },
            source=self.name,
        )

    def get_greeting(self) -> str:
        import datetime
        hour = datetime.datetime.now().hour
        if hour < 12:
            time_greeting = "Good Morning"
        elif hour < 17:
            time_greeting = "Good Afternoon"
        else:
            time_greeting = "Good Evening"

        name = self._settings.spark.user.name
        nickname = self._settings.spark.user.nickname

        return (
            f"{time_greeting} {name}.\n"
            f"Welcome back {nickname}.\n"
            f"All systems operational.\n"
            f"How may I assist you today?"
        )
