"""
SPARK QUANTUM AI OS — Unit Tests
Tests for core components: event bus, memory, security, agents.
"""
import asyncio
import json
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent))


# ── Event Bus ─────────────────────────────────────────────────────────────────

@pytest.fixture
def event_loop():
    loop = asyncio.new_event_loop()
    yield loop
    loop.close()


@pytest.fixture
def bus():
    from core.events.bus import EventBus
    return EventBus()


@pytest.mark.asyncio
async def test_event_bus_publish_subscribe(bus):
    received = []

    async def handler(event):
        received.append(event)

    bus.subscribe("test.event", handler)
    asyncio.create_task(bus.run())

    from core.events.bus import Event, EventType
    await bus.publish(Event(type="test.event", payload={"hello": "world"}))
    await asyncio.sleep(0.1)

    assert len(received) == 1
    assert received[0].payload["hello"] == "world"
    bus.stop()


@pytest.mark.asyncio
async def test_event_bus_wildcard(bus):
    received = []

    async def handler(event):
        received.append(event)

    bus.subscribe("agent.*", handler)
    asyncio.create_task(bus.run())

    from core.events.bus import Event, EventType
    await bus.publish(Event(type=EventType.AGENT_STARTED, payload={"agent": "test"}))
    await bus.publish(Event(type=EventType.AGENT_STOPPED, payload={"agent": "test"}))
    await asyncio.sleep(0.1)

    assert len(received) == 2
    bus.stop()


@pytest.mark.asyncio
async def test_event_bus_emit_helper(bus):
    received = []

    async def handler(event):
        received.append(event)

    bus.subscribe("system.ready", handler)
    asyncio.create_task(bus.run())

    from core.events.bus import EventType
    event = await bus.emit(EventType.SYSTEM_READY, {"version": "1.0"}, source="test")
    await asyncio.sleep(0.1)

    assert event.source == "test"
    assert len(received) == 1
    bus.stop()


# ── Short-Term Memory ─────────────────────────────────────────────────────────

def test_short_term_memory_store_get():
    from memory.memory_manager import ShortTermMemory
    stm = ShortTermMemory(max_entries=10)

    stm.store("key1", "value1")
    assert stm.get("key1") == "value1"


def test_short_term_memory_max_entries():
    from memory.memory_manager import ShortTermMemory
    stm = ShortTermMemory(max_entries=5)

    for i in range(10):
        stm.store(f"key{i}", f"value{i}")

    recent = stm.get_recent(10)
    assert len(recent) == 5


def test_short_term_memory_search():
    from memory.memory_manager import ShortTermMemory
    stm = ShortTermMemory()

    stm.store("topic", "intrusion detection systems")
    stm.store("other", "machine learning models")

    results = stm.search("intrusion")
    assert len(results) >= 1
    assert any("intrusion" in str(r["value"]) for r in results)


# ── Long-Term Memory ──────────────────────────────────────────────────────────

@pytest.fixture
def ltm(tmp_path):
    from memory.memory_manager import LongTermMemory
    return LongTermMemory(str(tmp_path / "test.db"))


def test_long_term_memory_store_get(ltm):
    ltm.store("project", "SPARK OS", category="projects")
    assert ltm.get("project") == "SPARK OS"


def test_long_term_memory_search(ltm):
    ltm.store("topic1", "neural networks", category="research")
    ltm.store("topic2", "deep learning", category="research")
    ltm.store("topic3", "accounting software", category="work")

    results = ltm.search("neural", category="research")
    assert any("neural" in str(r["value"]) for r in results)


def test_long_term_memory_preferences(ltm):
    ltm.set_preference("theme", "dark")
    ltm.set_preference("language", "en")

    assert ltm.get_preference("theme") == "dark"
    assert ltm.get_preference("missing", "default") == "default"


def test_long_term_memory_conversations(ltm):
    session = "session_001"
    ltm.add_conversation(session, "user", "Hello SPARK")
    ltm.add_conversation(session, "assistant", "Good morning, Vayala!")

    history = ltm.get_conversation(session)
    assert len(history) == 2
    assert history[0]["role"] == "user"


# ── Security ──────────────────────────────────────────────────────────────────

@pytest.fixture
def security(tmp_path):
    from security.security_manager import SecurityManager
    from core.settings import Settings
    # Minimal settings
    class MockSettings:
        class spark:
            class user:
                name = "Mr. Shree Ranjan"
        class security:
            audit_log = str(tmp_path / "audit.db")
    sec = SecurityManager.__new__(SecurityManager)
    from security.security_manager import AuditLog, Role
    sec._audit = AuditLog(str(tmp_path / "audit.db"))
    sec._actor_roles = {
        "Mr. Shree Ranjan": Role.ADMIN,
        "system": Role.ADMIN,
    }
    return sec


def test_security_admin_has_all_permissions(security):
    from security.security_manager import Permission
    assert security.check_permission("Mr. Shree Ranjan", Permission.FILE_DELETE)
    assert security.check_permission("Mr. Shree Ranjan", Permission.EXECUTE_CODE)


def test_security_guest_limited(security):
    from security.security_manager import Permission, Role
    security.set_role("guest_user", Role.GUEST)
    assert security.check_permission("guest_user", Permission.FILE_READ)
    assert not security.check_permission("guest_user", Permission.FILE_DELETE)
    assert not security.check_permission("guest_user", Permission.EXECUTE_CODE)


def test_security_requires_approval(security):
    from security.security_manager import Permission
    assert security.requires_approval(Permission.FILE_DELETE)
    assert security.requires_approval(Permission.SYSTEM_SHUTDOWN)
    assert not security.requires_approval(Permission.FILE_READ)


def test_security_audit_log(security):
    from security.security_manager import Permission
    security.check_permission("Mr. Shree Ranjan", Permission.FILE_READ, resource="test.txt")
    logs = security.get_audit_log(limit=10)
    assert len(logs) >= 1
    assert logs[0]["permission"] == "file_read"


# ── Citation Generator ────────────────────────────────────────────────────────

def test_citation_apa():
    from agents.research.research_agent import CitationGenerator
    citer = CitationGenerator()
    paper = {
        "title": "Deep Learning for Intrusion Detection",
        "authors": ["John Smith", "Jane Doe"],
        "year": 2023,
        "doi": "10.1234/test",
    }
    apa = citer.apa(paper)
    assert "2023" in apa
    assert "Deep Learning" in apa


def test_citation_ieee():
    from agents.research.research_agent import CitationGenerator
    citer = CitationGenerator()
    paper = {
        "title": "Neural Network Security",
        "authors": ["Alice Brown"],
        "year": 2022,
        "doi": "",
    }
    ieee = citer.ieee(paper, ref_num=3)
    assert "[3]" in ieee
    assert "Neural Network Security" in ieee


# ── Document Parsing ──────────────────────────────────────────────────────────

def test_document_section_parsing():
    from agents.document.document_agent import DocumentAgent

    agent = DocumentAgent.__new__(DocumentAgent)

    raw = """# Introduction
This is the introduction paragraph.

## Background
Some background information here.

## Methodology
The methodology section."""

    sections = agent._parse_sections(raw)
    assert len(sections) >= 2
    assert any("Introduction" in s.get("heading", "") for s in sections)


def test_slide_parsing():
    from agents.document.document_agent import DocumentAgent

    agent = DocumentAgent.__new__(DocumentAgent)
    raw = """SLIDE_TITLE: Introduction to AI
SLIDE_CONTENT: Overview of artificial intelligence
BULLETS:
- What is AI
- History of AI
- Applications

SLIDE_TITLE: Machine Learning
SLIDE_CONTENT: Core ML concepts
BULLETS:
- Supervised learning
- Unsupervised learning"""

    slides = agent._parse_slides(raw)
    assert len(slides) == 2
    assert slides[0]["title"] == "Introduction to AI"
    assert len(slides[0]["bullets"]) == 3


# ── Code Extractor ────────────────────────────────────────────────────────────

def test_code_extraction():
    from agents.coding.coding_agent import CodingAgent

    agent = CodingAgent.__new__(CodingAgent)

    response = """Here is the code:

```python
def hello():
    print("Hello, Vayala!")
```

This function prints a greeting."""

    code = agent._extract_code(response, "python")
    assert "def hello" in code
    assert "Hello, Vayala" in code


if __name__ == "__main__":
    pytest.main([__file__, "-v", "--tb=short"])
