"""
SPARK QUANTUM AI OS — Model Router
Routes requests to the right LLM based on task type and availability.
Falls back gracefully when providers are unavailable.
"""
from __future__ import annotations

import logging
from enum import Enum
from typing import Any, Dict, List, Optional

import httpx
import google.generativeai as genai

from core.settings import Settings, get_settings

logger = logging.getLogger("spark.router")


class TaskType(str, Enum):
    SIMPLE = "simple"
    CODING = "coding"
    RESEARCH = "research"
    VISION = "vision"
    REASONING = "reasoning"
    CONVERSATION = "conversation"


class ModelRouter:
    """
    Intelligent model router.
    Selects the best available model for each task type.
    Falls back through providers: primary → secondary → local ollama.
    """

    def __init__(self, settings: Optional[Settings] = None) -> None:
        self._settings = settings or get_settings()
        self._gemini_available = False
        self._available: Dict[str, bool] = {}
        self._init_clients()

    def _init_clients(self) -> None:
        providers = self._settings.models.providers

        gemini_key = providers.get("gemini", None)
        # Also check env variable directly as fallback
        import os
        api_key = os.environ.get("GEMINI_API_KEY") or (gemini_key.api_key if gemini_key else None)
        
        if api_key and not api_key.startswith("your_"):
            genai.configure(api_key=api_key)
            self._gemini_available = True
            self._available["gemini"] = True
            logger.info("Gemini client initialized")

        self._available["ollama"] = True  # Always try ollama as local fallback

    def _select_model(self, task_type: TaskType) -> tuple[str, str]:
        """Returns (provider, model_name)"""
        candidates = {
            TaskType.SIMPLE: [("gemini", "gemini-2.5-flash"), ("ollama", "llama3")],
            TaskType.CODING: [("gemini", "gemini-2.5-pro"), ("ollama", "codellama")],
            TaskType.RESEARCH: [("gemini", "gemini-2.5-pro"), ("ollama", "llama3")],
            TaskType.VISION: [("gemini", "gemini-2.5-pro")],
            TaskType.REASONING: [("gemini", "gemini-2.5-pro"), ("ollama", "llama3")],
            TaskType.CONVERSATION: [("gemini", "gemini-2.5-flash"), ("ollama", "llama3")],
        }

        for provider, model in candidates.get(task_type, [("ollama", "llama3")]):
            if self._available.get(provider):
                return provider, model

        return "ollama", "llama3"

    async def complete(
        self,
        messages: List[Dict[str, str]],
        task_type: TaskType = TaskType.SIMPLE,
        temperature: float = 0.7,
        max_tokens: int = 4096,
        system: Optional[str] = None,
    ) -> str:
        provider, model = self._select_model(task_type)
        logger.info(f"Routing {task_type.value} → {provider}/{model}")

        try:
            if provider == "gemini" and self._gemini_available:
                return await self._gemini_complete(messages, model, temperature, max_tokens, system)
            else:
                return await self._ollama_complete(messages, model, temperature, max_tokens, system)
        except Exception as e:
            logger.warning(f"{provider} failed ({e}), falling back to ollama")
            return await self._ollama_complete(messages, model, temperature, max_tokens, system)

    async def _gemini_complete(self, messages, model, temperature, max_tokens, system) -> str:
        # Convert standard dict messages to Gemini format
        formatted_messages = []
        for m in messages:
            role = "model" if m["role"] == "assistant" else "user"
            formatted_messages.append({"role": role, "parts": [m["content"]]})
            
        genai_model = genai.GenerativeModel(
            model_name=model,
            system_instruction=system
        )
        
        # We use run_in_executor to avoid blocking since standard genai is sync
        import asyncio
        loop = asyncio.get_event_loop()
        
        def _call_gemini():
            response = genai_model.generate_content(
                formatted_messages,
                generation_config=genai.types.GenerationConfig(
                    temperature=temperature,
                    max_output_tokens=max_tokens,
                )
            )
            return response.text
            
        return await loop.run_in_executor(None, _call_gemini)

    async def _ollama_complete(self, messages, model, temperature, max_tokens, system) -> str:
        if system:
            messages = [{"role": "system", "content": system}] + messages
        async with httpx.AsyncClient(timeout=120) as client:
            response = await client.post(
                "http://localhost:11434/api/chat",
                json={
                    "model": model,
                    "messages": messages,
                    "stream": False,
                    "options": {"temperature": temperature, "num_predict": max_tokens},
                },
            )
            response.raise_for_status()
            return response.json()["message"]["content"]

    def get_available_providers(self) -> Dict[str, bool]:
        return dict(self._available)
