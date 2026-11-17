"""
Tests for POST /chat, the endpoint the Spark Android companion uses to hand
requests to desktop SPARK and wait for the reply.
"""
import asyncio

import pytest

fastapi = pytest.importorskip("fastapi")
httpx = pytest.importorskip("httpx")

from api import server  # noqa: E402
from core.events.bus import EventBus, EventType  # noqa: E402


class FakeExecutive:
    """Answers each user.command the way ExecutiveAgent does, with a correlation id."""

    def __init__(self, bus: EventBus, reply: str = "On it.", delay: float = 0.05):
        self.bus, self.reply, self.delay = bus, reply, delay
        bus.subscribe(EventType.USER_COMMAND, self._on_command)

    async def _on_command(self, event):
        await asyncio.sleep(self.delay)
        # Unrelated chatter that must be ignored.
        await self.bus.emit(EventType.AGENT_STATUS_UPDATE, {"message": "noise", "speak": True},
                            source="other", correlation_id="someone-else")
        await self.bus.emit(
            EventType.AGENT_STATUS_UPDATE,
            {
                "agent": "executive_agent",
                "message": f"{self.reply} ({event.payload['text']})",
                "speak": True,
                "plan": {"plan": [{"description": "search papers"}]},
                "correlation_id": event.id,
            },
            source="executive_agent",
            correlation_id=event.id,
        )


@pytest.fixture
async def client(monkeypatch):
    monkeypatch.delenv("SPARK_API_TOKEN", raising=False)
    bus = EventBus()
    bus_task = asyncio.create_task(bus.run())
    executive = FakeExecutive(bus)
    server.set_context({"bus": bus})
    transport = httpx.ASGITransport(app=server.app)
    async with httpx.AsyncClient(transport=transport, base_url="http://test") as c:
        c.executive = executive
        yield c
    bus.stop()
    bus_task.cancel()
    server.set_context(None)


async def test_chat_returns_matching_reply(client):
    r = await client.post("/chat", json={"text": "research black holes"})
    assert r.status_code == 200
    body = r.json()
    assert body["finished"] is True
    assert body["reply"] == "On it. (research black holes)"
    assert body["steps"] == ["search papers"]


async def test_concurrent_chats_get_their_own_replies(client):
    a, b = await asyncio.gather(
        client.post("/chat", json={"text": "one"}),
        client.post("/chat", json={"text": "two"}),
    )
    assert a.json()["reply"].endswith("(one)")
    assert b.json()["reply"].endswith("(two)")


async def test_chat_timeout_reports_unfinished(client):
    client.executive.delay = 10
    r = await client.post("/chat", json={"text": "slow", "timeout_seconds": 0.1})
    assert r.status_code == 200
    assert r.json()["finished"] is False


async def test_empty_text_rejected(client):
    r = await client.post("/chat", json={"text": "   "})
    assert r.status_code == 400


async def test_token_required_when_configured(client, monkeypatch):
    monkeypatch.setenv("SPARK_API_TOKEN", "s3cret")
    assert (await client.post("/chat", json={"text": "hi"})).status_code == 401
    bad = await client.post("/chat", json={"text": "hi"}, headers={"Authorization": "Bearer nope"})
    assert bad.status_code == 401
    ok = await client.post("/chat", json={"text": "hi"}, headers={"Authorization": "Bearer s3cret"})
    assert ok.status_code == 200
