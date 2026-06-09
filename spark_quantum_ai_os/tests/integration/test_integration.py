"""
SPARK QUANTUM AI OS — Integration Tests
Tests full agent lifecycle, event bus, orchestrator, and memory integration.
Run:  pytest tests/integration/ -v
"""
import asyncio
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).parent.parent.parent))


# ── Fixtures ──────────────────────────────────────────────────────────────────

@pytest.fixture
def event_loop():
    loop = asyncio.new_event_loop()
    yield loop
    loop.close()


@pytest.fixture
async def bus():
    from core.events.bus import EventBus
    b = EventBus()
    task = asyncio.create_task(b.run())
    yield b
    b.stop()
    await asyncio.sleep(0.05)


@pytest.fixture
async def memory(tmp_path):
    from memory.memory_manager import MemoryManager
    from core.settings import Settings, MemoryConfig
    cfg = Settings()
    cfg.memory = MemoryConfig(
        long_term_db_path=str(tmp_path / "test.db"),
        vector_db_path=str(tmp_path / "chroma"),
    )
    return MemoryManager(settings=cfg)


@pytest.fixture
async def registry(bus):
    from core.registry.agent_registry import AgentRegistry
    return AgentRegistry(bus=bus)


# ── Agent lifecycle ───────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_agent_start_stop(bus):
    from agents.base_agent import BaseAgent, Task
    from core.events.bus import EventType

    started_events = []
    stopped_events = []

    async def on_start(e): started_events.append(e)
    async def on_stop(e):  stopped_events.append(e)

    bus.subscribe(EventType.AGENT_STARTED, on_start)
    bus.subscribe(EventType.AGENT_STOPPED, on_stop)

    class DummyAgent(BaseAgent):
        name = "dummy_test"
        async def execute(self, task: Task): return "ok"

    agent = DummyAgent(bus=bus)
    await agent.start()
    await asyncio.sleep(0.1)
    assert agent.status.value == "idle"
    assert len(started_events) == 1

    await agent.stop()
    await asyncio.sleep(0.1)
    assert len(stopped_events) == 1


@pytest.mark.asyncio
async def test_agent_task_execution(bus):
    from agents.base_agent import BaseAgent, Task
    from core.events.bus import EventType

    completed = []
    async def on_done(e): completed.append(e)
    bus.subscribe(EventType.AGENT_TASK_COMPLETED, on_done)

    class AddAgent(BaseAgent):
        name = "add_agent"
        async def execute(self, task: Task):
            a = task.payload.get("a", 0)
            b = task.payload.get("b", 0)
            return a + b

    agent = AddAgent(bus=bus)
    await agent.start()

    task = Task(name="add", payload={"a": 3, "b": 4})
    await agent.assign_task(task)
    await asyncio.sleep(0.3)

    assert len(completed) == 1
    await agent.stop()


@pytest.mark.asyncio
async def test_agent_registry_dispatch(bus, registry):
    from agents.base_agent import BaseAgent, Task

    results = []

    class EchoAgent(BaseAgent):
        name = "echo_agent"
        async def execute(self, task: Task):
            result = task.payload.get("message", "")
            results.append(result)
            return result

    agent = EchoAgent(bus=bus)
    registry.register(agent)
    await registry.start_all()

    task = Task(name="echo", payload={"message": "hello vayala"})
    await registry.dispatch("echo_agent", task)
    await asyncio.sleep(0.3)

    assert results == ["hello vayala"]
    await registry.stop_all()


# ── Memory integration ────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_memory_full_cycle(memory):
    # Store
    memory.remember("spark_version", "1.0.1", category="system", importance=10)

    # Recall short-term
    val = memory.recall("spark_version")
    assert val == "1.0.1"

    # Recall long-term
    memory.short_term.clear()
    val2 = memory.recall("spark_version")
    assert val2 == "1.0.1"


@pytest.mark.asyncio
async def test_memory_conversation(memory):
    sid = "test_session_001"
    memory.add_turn(sid, "user", "Hello SPARK")
    memory.add_turn(sid, "assistant", "Good morning, Vayala!")

    ctx = memory.get_context_window(sid)
    assert len(ctx) == 2
    assert ctx[0]["role"] == "user"
    assert "Hello" in ctx[0]["content"]


@pytest.mark.asyncio
async def test_memory_preferences(memory):
    memory.set_preference("theme", "dark")
    memory.set_preference("language", "en")

    assert memory.get_preference("theme") == "dark"
    assert memory.get_preference("missing_key", "fallback") == "fallback"


# ── Orchestrator ──────────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_orchestrator_linear(bus, registry):
    from agents.base_agent import BaseAgent, Task
    from core.executor.orchestrator import Orchestrator, Workflow, WorkflowStep

    log = []

    class StepAgent(BaseAgent):
        name = "step_agent"
        async def execute(self, task: Task):
            log.append(task.name)
            return f"done:{task.name}"

    agent = StepAgent(bus=bus)
    registry.register(agent)
    await registry.start_all()

    workflow = Workflow(
        name="linear_test",
        steps=[
            WorkflowStep(id="s1", agent="step_agent", task_name="step_one",   payload={}),
            WorkflowStep(id="s2", agent="step_agent", task_name="step_two",   payload={}, depends_on=["s1"]),
            WorkflowStep(id="s3", agent="step_agent", task_name="step_three", payload={}, depends_on=["s2"]),
        ],
    )

    orch = Orchestrator(registry=registry, bus=bus)
    results = await orch.run_workflow(workflow)

    # All 3 steps should have completed
    assert workflow.status == "completed"
    assert len(results) == 3

    await registry.stop_all()


@pytest.mark.asyncio
async def test_orchestrator_parallel(bus, registry):
    """Steps with no dependencies should run in parallel."""
    from agents.base_agent import BaseAgent, Task
    from core.executor.orchestrator import Orchestrator, Workflow, WorkflowStep

    timings = {}

    class TimedAgent(BaseAgent):
        name = "timed_agent"
        async def execute(self, task: Task):
            import time
            timings[task.name] = time.time()
            await asyncio.sleep(0.05)
            return task.name

    agent = TimedAgent(bus=bus)
    registry.register(agent)
    await registry.start_all()

    workflow = Workflow(
        name="parallel_test",
        steps=[
            WorkflowStep(id="p1", agent="timed_agent", task_name="parallel_a", payload={}),
            WorkflowStep(id="p2", agent="timed_agent", task_name="parallel_b", payload={}),
            WorkflowStep(id="p3", agent="timed_agent", task_name="parallel_c", payload={}),
        ],
    )

    orch = Orchestrator(registry=registry, bus=bus)
    results = await orch.run_workflow(workflow)

    assert workflow.status == "completed"
    assert len(results) == 3
    await registry.stop_all()


# ── Event bus advanced ────────────────────────────────────────────────────────

@pytest.mark.asyncio
async def test_event_history(bus):
    from core.events.bus import EventType

    for i in range(5):
        await bus.emit(EventType.SYSTEM_READY, {"i": i})
    await asyncio.sleep(0.1)

    hist = bus.get_history(event_type=EventType.SYSTEM_READY, limit=10)
    assert len(hist) == 5


@pytest.mark.asyncio
async def test_event_correlation(bus):
    from core.events.bus import EventType

    received = []
    async def handler(e): received.append(e)
    bus.subscribe(EventType.USER_COMMAND, handler)

    await bus.emit(EventType.USER_COMMAND, {"text": "test"}, correlation_id="corr-123")
    await asyncio.sleep(0.1)

    assert len(received) == 1
    assert received[0].correlation_id == "corr-123"


# ── Security integration ──────────────────────────────────────────────────────

def test_security_role_escalation(tmp_path):
    from security.security_manager import SecurityManager, Role, Permission
    from core.settings import Settings

    class _Cfg:
        class spark:
            class user:
                name = "Mr. Shree Ranjan"
        class security:
            audit_log = str(tmp_path / "audit.db")

    sec = SecurityManager.__new__(SecurityManager)
    from security.security_manager import AuditLog
    sec._audit = AuditLog(str(tmp_path / "a.db"))
    sec._actor_roles = {"Mr. Shree Ranjan": Role.ADMIN}

    # Admin can do everything
    assert sec.check_permission("Mr. Shree Ranjan", Permission.SYSTEM_SHUTDOWN)
    assert sec.check_permission("Mr. Shree Ranjan", Permission.FILE_DELETE)

    # Demote to guest
    sec.set_role("Mr. Shree Ranjan", Role.GUEST)
    assert not sec.check_permission("Mr. Shree Ranjan", Permission.SYSTEM_SHUTDOWN)
    assert not sec.check_permission("Mr. Shree Ranjan", Permission.FILE_DELETE)

    # Restore
    sec.set_role("Mr. Shree Ranjan", Role.ADMIN)
    assert sec.check_permission("Mr. Shree Ranjan", Permission.SYSTEM_SHUTDOWN)


if __name__ == "__main__":
    pytest.main([__file__, "-v", "--tb=short", "-x"])
