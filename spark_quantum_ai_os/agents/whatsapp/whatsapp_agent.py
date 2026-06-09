"""
SPARK QUANTUM AI OS — WhatsApp Agent
Automates WhatsApp Web: read messages, draft replies, send (with approval),
manage groups, summarize conversations, schedule messages.
"""
from __future__ import annotations

import asyncio
import logging
from datetime import datetime
from typing import Any, Dict, List, Optional

from agents.base_agent import AgentCapability, BaseAgent, Task
from core.events.bus import EventBus, EventType
from core.router.model_router import ModelRouter, TaskType

logger = logging.getLogger("spark.whatsapp")

WHATSAPP_URL = "https://web.whatsapp.com"


class WhatsAppAgent(BaseAgent):
    name = "whatsapp_agent"
    description = "Manages WhatsApp Web: read, summarise, draft and send messages."
    capabilities = [
        AgentCapability("open_whatsapp",    "Open WhatsApp Web in browser"),
        AgentCapability("read_messages",    "Read recent messages from a contact/group"),
        AgentCapability("summarize_chat",   "Summarise a conversation"),
        AgentCapability("draft_reply",      "Draft a reply using AI"),
        AgentCapability("send_message",     "Send a message (requires approval)", requires_approval=True),
        AgentCapability("list_chats",       "List recent chats"),
        AgentCapability("schedule_message", "Schedule a message for later", requires_approval=True),
    ]

    def __init__(
        self,
        router: Optional[ModelRouter] = None,
        bus: Optional[EventBus] = None,
        settings=None,
    ) -> None:
        super().__init__(bus)
        self._router = router or ModelRouter()
        self._browser_agent = None   # injected after registry is built
        self._scheduled: List[Dict] = []

    def set_browser_agent(self, agent) -> None:
        self._browser_agent = agent

    # ── Internal helpers ──────────────────────────────────────────────────────

    async def _get_page(self):
        """Return the Playwright page from the browser agent's session."""
        if not self._browser_agent:
            raise RuntimeError("Browser agent not connected to WhatsApp agent.")
        return self._browser_agent._session.page

    async def _ensure_open(self) -> None:
        page = await self._get_page()
        if WHATSAPP_URL not in (page.url or ""):
            await page.goto(WHATSAPP_URL, wait_until="domcontentloaded")
            # Wait for the chat list to appear (user may need to scan QR once)
            try:
                await page.wait_for_selector("div#pane-side", timeout=30_000)
            except Exception:
                raise RuntimeError(
                    "WhatsApp Web did not load. "
                    "Please scan the QR code manually in the browser window, then retry."
                )

    async def _open_chat(self, contact: str) -> bool:
        """Open a chat by contact name or phone number."""
        page = await self._get_page()
        await self._ensure_open()

        # Click the search box
        await page.click('div[data-testid="chat-list-search"]', timeout=10_000)
        await asyncio.sleep(0.4)
        await page.fill('div[data-testid="chat-list-search"] input', contact)
        await asyncio.sleep(1.2)

        # Click the first result
        results = await page.query_selector_all("div[data-testid='cell-frame-container']")
        if not results:
            logger.warning(f"No WhatsApp chat found for: {contact}")
            return False
        await results[0].click()
        await asyncio.sleep(0.8)
        return True

    async def _scrape_messages(self, limit: int = 30) -> List[Dict]:
        page = await self._get_page()
        msgs = []
        try:
            bubbles = await page.query_selector_all(
                "div.message-in, div.message-out"
            )
            for bubble in bubbles[-limit:]:
                is_outgoing = "message-out" in (await bubble.get_attribute("class") or "")
                text_el = await bubble.query_selector("span.selectable-text")
                time_el = await bubble.query_selector("div[data-testid='msg-meta'] span")
                text = (await text_el.inner_text()).strip() if text_el else ""
                time = (await time_el.inner_text()).strip() if time_el else ""
                if text:
                    msgs.append({
                        "direction": "out" if is_outgoing else "in",
                        "text": text,
                        "time": time,
                    })
        except Exception as e:
            logger.error(f"Message scrape error: {e}")
        return msgs

    # ── execute ───────────────────────────────────────────────────────────────

    async def execute(self, task: Task) -> Any:
        action = task.payload.get("action", "")
        params = task.payload.get("params", {})

        if action == "open_whatsapp":
            return await self._action_open()
        elif action == "list_chats":
            return await self._action_list_chats()
        elif action == "read_messages":
            return await self._action_read(params)
        elif action == "summarize_chat":
            return await self._action_summarize(params)
        elif action == "draft_reply":
            return await self._action_draft(params)
        elif action == "send_message":
            return await self._action_send(params)
        elif action == "schedule_message":
            return await self._action_schedule(params)
        else:
            raise ValueError(f"Unknown WhatsApp action: {action}")

    async def _action_open(self) -> Dict:
        await self._ensure_open()
        return {"status": "WhatsApp Web open"}

    async def _action_list_chats(self) -> List[Dict]:
        page = await self._get_page()
        await self._ensure_open()
        chats = []
        try:
            items = await page.query_selector_all(
                "div[data-testid='cell-frame-container']"
            )
            for item in items[:20]:
                name_el  = await item.query_selector("span[data-testid='cell-frame-title']")
                msg_el   = await item.query_selector("div[data-testid='last-msg-status'] span.ggj6brxn")
                time_el  = await item.query_selector("div.g0rxnol2 span")
                name = (await name_el.inner_text()).strip() if name_el else ""
                last = (await msg_el.inner_text()).strip()  if msg_el  else ""
                time = (await time_el.inner_text()).strip() if time_el else ""
                if name:
                    chats.append({"name": name, "last_message": last, "time": time})
        except Exception as e:
            logger.error(f"List chats error: {e}")
        return chats

    async def _action_read(self, params: Dict) -> Dict:
        contact = params.get("contact", "")
        limit   = params.get("limit", 30)
        if not contact:
            raise ValueError("contact param required")
        opened = await self._open_chat(contact)
        if not opened:
            return {"error": f"Chat not found: {contact}"}
        messages = await self._scrape_messages(limit)
        return {"contact": contact, "count": len(messages), "messages": messages}

    async def _action_summarize(self, params: Dict) -> str:
        contact = params.get("contact", "")
        data    = await self._action_read({"contact": contact, "limit": 50})
        if "error" in data:
            return data["error"]

        convo = "\n".join(
            f"[{m['direction'].upper()}] {m['time']}: {m['text']}"
            for m in data["messages"]
        )
        prompt = (
            f"Summarise this WhatsApp conversation with {contact}.\n\n"
            f"{convo}\n\n"
            "Give: key topics discussed, action items, anything needing a reply."
        )
        return await self._router.complete(
            messages=[{"role": "user", "content": prompt}],
            task_type=TaskType.SIMPLE,
            max_tokens=800,
        )

    async def _action_draft(self, params: Dict) -> str:
        contact = params.get("contact", "")
        context = params.get("context", "")
        tone    = params.get("tone", "friendly and professional")

        if not context:
            data    = await self._action_read({"contact": contact, "limit": 20})
            messages = data.get("messages", [])
            context = "\n".join(f"[{m['direction']}] {m['text']}" for m in messages[-10:])

        prompt = (
            f"Draft a {tone} reply to this WhatsApp conversation:\n\n"
            f"{context}\n\n"
            "Write only the reply text, nothing else."
        )
        return await self._router.complete(
            messages=[{"role": "user", "content": prompt}],
            task_type=TaskType.SIMPLE,
            max_tokens=400,
        )

    async def _action_send(self, params: Dict) -> Dict:
        contact = params.get("contact", "")
        message = params.get("message", "")
        if not contact or not message:
            raise ValueError("contact and message params required")

        opened = await self._open_chat(contact)
        if not opened:
            return {"error": f"Chat not found: {contact}"}

        page = await self._get_page()
        box  = await page.query_selector('div[data-testid="conversation-compose-box-input"]')
        if not box:
            return {"error": "Could not find message input box"}

        await box.click()
        await asyncio.sleep(0.3)
        # Type character by character to avoid clipboard issues
        for char in message:
            await box.type(char, delay=20)
        await asyncio.sleep(0.3)
        await page.keyboard.press("Enter")
        await asyncio.sleep(0.5)

        logger.info(f"WhatsApp message sent to {contact}")
        await self._bus.emit(
            EventType.AGENT_STATUS_UPDATE,
            {"agent": self.name, "message": f"Sent message to {contact}"},
            source=self.name,
        )
        return {"status": "sent", "contact": contact, "message": message}

    async def _action_schedule(self, params: Dict) -> Dict:
        contact    = params.get("contact", "")
        message    = params.get("message", "")
        send_at_str = params.get("send_at", "")   # ISO format: "2026-06-09T15:30:00"

        if not all([contact, message, send_at_str]):
            raise ValueError("contact, message, and send_at required")

        entry = {
            "contact": contact,
            "message": message,
            "send_at": send_at_str,
            "created_at": datetime.utcnow().isoformat(),
        }
        self._scheduled.append(entry)
        asyncio.create_task(self._fire_scheduled(entry))
        return {"status": "scheduled", **entry}

    async def _fire_scheduled(self, entry: Dict) -> None:
        from datetime import timezone
        target = datetime.fromisoformat(entry["send_at"])
        now    = datetime.utcnow().replace(tzinfo=None)
        delay  = (target - now).total_seconds()
        if delay > 0:
            await asyncio.sleep(delay)
        await self._action_send({"contact": entry["contact"], "message": entry["message"]})
        self._scheduled.remove(entry)
