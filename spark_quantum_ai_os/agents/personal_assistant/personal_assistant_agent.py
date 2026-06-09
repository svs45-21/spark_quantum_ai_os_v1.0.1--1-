"""
SPARK QUANTUM AI OS — Personal Assistant Agent
Handles: reminders, calendar-style scheduling, weather, news summaries,
general Q&A, jokes, unit conversion, and day planning.
"""
from __future__ import annotations

import asyncio
import json
import logging
from datetime import datetime, timedelta
from typing import Any, Dict, List, Optional

import httpx

from agents.base_agent import AgentCapability, BaseAgent, Task
from core.events.bus import EventBus, EventType
from core.router.model_router import ModelRouter, TaskType

logger = logging.getLogger("spark.assistant")


class ReminderStore:
    """In-memory reminder store with asyncio-based firing."""

    def __init__(self, bus) -> None:
        self._bus = bus
        self._reminders: List[Dict] = []

    def add(self, text: str, fire_at: datetime) -> Dict:
        r = {"id": len(self._reminders), "text": text, "fire_at": fire_at.isoformat(), "fired": False}
        self._reminders.append(r)
        asyncio.create_task(self._schedule(r, fire_at))
        return r

    async def _schedule(self, reminder: Dict, fire_at: datetime) -> None:
        delay = (fire_at - datetime.utcnow()).total_seconds()
        if delay > 0:
            await asyncio.sleep(delay)
        if not reminder["fired"]:
            reminder["fired"] = True
            await self._bus.emit(
                EventType.AGENT_STATUS_UPDATE,
                {"agent": "personal_assistant", "message": f"⏰ Reminder: {reminder['text']}", "speak": True},
                source="personal_assistant",
            )

    def list_upcoming(self) -> List[Dict]:
        now = datetime.utcnow().isoformat()
        return [r for r in self._reminders if not r["fired"] and r["fire_at"] > now]


class PersonalAssistantAgent(BaseAgent):
    name = "personal_assistant"
    description = "Personal assistant: reminders, weather, news, Q&A, day planning."
    capabilities = [
        AgentCapability("set_reminder",    "Set a reminder for a specific time"),
        AgentCapability("list_reminders",  "List upcoming reminders"),
        AgentCapability("get_weather",     "Get current weather for a city"),
        AgentCapability("get_news",        "Get latest news headlines"),
        AgentCapability("answer_question", "Answer a general knowledge question"),
        AgentCapability("plan_day",        "Create a structured daily plan"),
        AgentCapability("convert_units",   "Convert between units"),
        AgentCapability("tell_joke",       "Tell a joke"),
        AgentCapability("motivate",        "Give a motivational message"),
        AgentCapability("what_time",       "Tell the current time and date"),
    ]

    def __init__(
        self,
        router: Optional[ModelRouter] = None,
        bus: Optional[EventBus] = None,
        settings=None,
    ) -> None:
        super().__init__(bus)
        from core.settings import get_settings
        self._cfg     = settings or get_settings()
        self._router  = router or ModelRouter()
        self._store   = ReminderStore(self._bus)

    async def execute(self, task: Task) -> Any:
        action = task.payload.get("action", "")
        params = task.payload.get("params", {})

        dispatch = {
            "set_reminder":    self._set_reminder,
            "list_reminders":  self._list_reminders,
            "get_weather":     self._get_weather,
            "get_news":        self._get_news,
            "answer_question": self._answer_question,
            "plan_day":        self._plan_day,
            "convert_units":   self._convert_units,
            "tell_joke":       self._tell_joke,
            "motivate":        self._motivate,
            "what_time":       self._what_time,
        }
        fn = dispatch.get(action)
        if not fn:
            raise ValueError(f"Unknown assistant action: {action}")
        return await fn(params)

    # ── Reminders ─────────────────────────────────────────────────────────────

    async def _set_reminder(self, params: Dict) -> Dict:
        text      = params.get("text", "Reminder")
        minutes   = params.get("minutes", 0)
        hours     = params.get("hours", 0)
        iso_time  = params.get("at", "")

        if iso_time:
            fire_at = datetime.fromisoformat(iso_time)
        else:
            fire_at = datetime.utcnow() + timedelta(hours=hours, minutes=minutes)

        r = self._store.add(text, fire_at)
        local_str = fire_at.strftime("%H:%M on %d %b %Y")
        return {"status": "set", "reminder": text, "fires_at": local_str, "id": r["id"]}

    async def _list_reminders(self, params: Dict) -> List[Dict]:
        return self._store.list_upcoming()

    # ── Weather (Open-Meteo — free, no key) ───────────────────────────────────

    async def _get_weather(self, params: Dict) -> Dict:
        city = params.get("city", "Udupi")
        try:
            # Geocode
            async with httpx.AsyncClient(timeout=10) as client:
                geo = await client.get(
                    "https://geocoding-api.open-meteo.com/v1/search",
                    params={"name": city, "count": 1, "language": "en", "format": "json"},
                )
                geo.raise_for_status()
                results = geo.json().get("results", [])
                if not results:
                    return {"error": f"City not found: {city}"}
                loc = results[0]
                lat, lon = loc["latitude"], loc["longitude"]

                # Weather
                wx = await client.get(
                    "https://api.open-meteo.com/v1/forecast",
                    params={
                        "latitude": lat, "longitude": lon,
                        "current": "temperature_2m,relative_humidity_2m,wind_speed_10m,weather_code",
                        "timezone": "auto",
                    },
                )
                wx.raise_for_status()
                current = wx.json().get("current", {})

            WMO = {
                0:"Clear sky", 1:"Mainly clear", 2:"Partly cloudy", 3:"Overcast",
                45:"Fog", 51:"Light drizzle", 61:"Light rain", 63:"Moderate rain",
                71:"Light snow", 80:"Showers", 95:"Thunderstorm",
            }
            code  = current.get("weather_code", 0)
            desc  = WMO.get(code, f"Code {code}")
            temp  = current.get("temperature_2m", "?")
            hum   = current.get("relative_humidity_2m", "?")
            wind  = current.get("wind_speed_10m", "?")

            return {
                "city": loc.get("name", city),
                "country": loc.get("country", ""),
                "temperature_c": temp,
                "condition": desc,
                "humidity_pct": hum,
                "wind_kmh": wind,
                "summary": f"{desc}, {temp}°C, humidity {hum}%, wind {wind} km/h",
            }
        except Exception as e:
            logger.error(f"Weather fetch failed: {e}")
            return {"error": str(e)}

    # ── News (GNews free tier — no key for basic) ─────────────────────────────

    async def _get_news(self, params: Dict) -> List[Dict]:
        topic   = params.get("topic", "technology")
        limit   = params.get("limit", 5)
        try:
            async with httpx.AsyncClient(timeout=10) as client:
                # Use RSS as fallback (no API key needed)
                r = await client.get(
                    f"https://news.google.com/rss/search?q={topic}&hl=en-IN&gl=IN&ceid=IN:en",
                    follow_redirects=True,
                )
                r.raise_for_status()
                import re
                items = re.findall(r"<item>(.*?)</item>", r.text, re.DOTALL)
                news  = []
                for item in items[:limit]:
                    def tag(t):
                        m = re.search(rf"<{t}[^>]*>(.*?)</{t}>", item, re.DOTALL)
                        return re.sub(r"<[^>]+>", "", m.group(1)).strip() if m else ""
                    news.append({"title": tag("title"), "source": tag("source"), "date": tag("pubDate")})
                return news
        except Exception as e:
            logger.error(f"News fetch failed: {e}")
            return [{"error": str(e)}]

    # ── Q&A ───────────────────────────────────────────────────────────────────

    async def _answer_question(self, params: Dict) -> str:
        question = params.get("question", "")
        return await self._router.complete(
            messages=[{"role": "user", "content": question}],
            task_type=TaskType.SIMPLE,
            system=(
                f"You are SPARK, a helpful personal assistant for "
                f"{self._cfg.spark.user.name} ({self._cfg.spark.user.nickname}). "
                "Be concise, friendly, and accurate."
            ),
            max_tokens=600,
        )

    # ── Day planning ──────────────────────────────────────────────────────────

    async def _plan_day(self, params: Dict) -> str:
        tasks   = params.get("tasks", [])
        wake    = params.get("wake_time", "07:00")
        sleep   = params.get("sleep_time", "23:00")
        date    = params.get("date", datetime.now().strftime("%A, %d %B %Y"))

        task_list = "\n".join(f"- {t}" for t in tasks) if tasks else "No specific tasks provided."
        prompt = (
            f"Create a structured, realistic daily schedule for {self._cfg.spark.user.nickname} "
            f"on {date}.\n\nWake: {wake}  |  Sleep: {sleep}\n\nTasks:\n{task_list}\n\n"
            "Include: morning routine, focused work blocks, breaks, meals, exercise, "
            "and wind-down. Format as a timeline."
        )
        return await self._router.complete(
            messages=[{"role": "user", "content": prompt}],
            task_type=TaskType.SIMPLE,
            max_tokens=800,
        )

    # ── Unit conversion ───────────────────────────────────────────────────────

    async def _convert_units(self, params: Dict) -> str:
        value    = params.get("value", 1)
        from_u   = params.get("from", "km")
        to_u     = params.get("to", "miles")
        prompt   = f"Convert {value} {from_u} to {to_u}. Give the result and show the formula used."
        return await self._router.complete(
            messages=[{"role": "user", "content": prompt}],
            task_type=TaskType.SIMPLE,
            max_tokens=200,
        )

    # ── Fun ───────────────────────────────────────────────────────────────────

    async def _tell_joke(self, params: Dict) -> str:
        category = params.get("category", "programming")
        prompt   = f"Tell a short, clever {category} joke. Keep it clean and fun."
        return await self._router.complete(
            messages=[{"role": "user", "content": prompt}],
            task_type=TaskType.SIMPLE,
            max_tokens=150,
        )

    async def _motivate(self, params: Dict) -> str:
        context = params.get("context", "general productivity")
        prompt  = (
            f"Give {self._cfg.spark.user.nickname} a short, powerful motivational message "
            f"for: {context}. Be genuine, not generic."
        )
        return await self._router.complete(
            messages=[{"role": "user", "content": prompt}],
            task_type=TaskType.SIMPLE,
            max_tokens=200,
        )

    async def _what_time(self, params: Dict) -> Dict:
        now = datetime.now()
        return {
            "time":     now.strftime("%I:%M %p"),
            "date":     now.strftime("%A, %d %B %Y"),
            "iso":      now.isoformat(),
            "greeting": (
                "Good morning" if now.hour < 12
                else "Good afternoon" if now.hour < 17
                else "Good evening"
            ),
        }
