"""
SPARK QUANTUM AI OS — Main Application  v1.0.1
Entry point. Boots all subsystems, starts UI / CLI / API.

Flags:
  (none)      Launch PySide6 UI (default)
  --cli       CLI mode (no PySide6 needed)
  --api       Start FastAPI server on port 8765
  --all       CLI + API server together
"""
from __future__ import annotations

import asyncio
import logging
import sys
from pathlib import Path

ROOT = Path(__file__).parent
sys.path.insert(0, str(ROOT))


def setup_logging(level: str = "INFO") -> None:
    Path("logs").mkdir(exist_ok=True)
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s | %(levelname)-8s | %(name)s | %(message)s",
        handlers=[
            logging.StreamHandler(sys.stdout),
            logging.FileHandler("logs/spark.log", encoding="utf-8"),
        ],
    )


setup_logging()
logger = logging.getLogger("spark.main")


# ── Boot ──────────────────────────────────────────────────────────────────────

async def boot_subsystems() -> dict:
    logger.info("=" * 62)
    logger.info("  SPARK QUANTUM AI OS v1.0.1 — Booting")
    logger.info("=" * 62)

    from core.settings import get_settings
    settings = get_settings()
    logger.info(f"  User     : {settings.spark.user.name}  ({settings.spark.user.nickname})")

    for d in ["data", "logs", "data/chroma", "data/documents",
              "data/research", "data/code"]:
        Path(d).mkdir(parents=True, exist_ok=True)

    # Event bus
    from core.events.bus import get_event_bus, EventType
    bus = get_event_bus()
    bus_task = asyncio.create_task(bus.run())
    logger.info("  [OK] Event bus")

    # Security
    from security.security_manager import get_security
    security = get_security()
    logger.info("  [OK] Security")

    # Memory
    from memory.memory_manager import get_memory
    memory = get_memory()
    logger.info("  [OK] Memory")

    # Model router
    from core.router.model_router import ModelRouter
    router = ModelRouter(settings)
    for p, avail in router.get_available_providers().items():
        logger.info(f"  {'[OK]' if avail else '[  ]'} Model provider: {p}")

    # Agent registry
    from core.registry.agent_registry import get_registry
    registry = get_registry()

    # ── Register all agents ───────────────────────────────────────────────
    from agents.executive.executive_agent        import ExecutiveAgent
    from agents.desktop.desktop_agent            import DesktopAgent
    from agents.vision.vision_agent              import VisionAgent
    from agents.browser.browser_agent            import BrowserAgent
    from agents.document.document_agent          import DocumentAgent
    from agents.research.research_agent          import ResearchAgent
    from agents.coding.coding_agent              import CodingAgent
    from agents.whatsapp.whatsapp_agent          import WhatsAppAgent
    from agents.personal_assistant.personal_assistant_agent import PersonalAssistantAgent
    from agents.monitoring.monitoring_agent      import MonitoringAgent

    executive   = ExecutiveAgent(router=router, bus=bus)
    browser     = BrowserAgent(bus=bus, settings=settings)
    wa_agent    = WhatsAppAgent(router=router, bus=bus)
    wa_agent.set_browser_agent(browser)

    monitor     = MonitoringAgent(bus=bus, registry=registry)

    registry.register(executive)
    registry.register(DesktopAgent(bus=bus, settings=settings))
    registry.register(VisionAgent(router=router, bus=bus, settings=settings))
    registry.register(browser)
    registry.register(DocumentAgent(router=router, bus=bus, settings=settings))
    registry.register(ResearchAgent(router=router, bus=bus))
    registry.register(CodingAgent(router=router, bus=bus))
    registry.register(wa_agent)
    registry.register(PersonalAssistantAgent(router=router, bus=bus, settings=settings))
    registry.register(monitor)

    # Orchestrator
    from core.executor.orchestrator import Orchestrator
    orchestrator = Orchestrator(registry=registry, bus=bus)

    # Start all agents
    await registry.start_all()
    logger.info(f"  [OK] {len(registry)} agents started")

    # Start monitoring watcher
    asyncio.create_task(_start_monitor(registry))

    await bus.emit(EventType.SYSTEM_READY, {"version": settings.spark.version}, source="main")
    logger.info("\n" + executive.get_greeting() + "\n")

    return {
        "settings":     settings,
        "bus":          bus,
        "bus_task":     bus_task,
        "security":     security,
        "memory":       memory,
        "router":       router,
        "registry":     registry,
        "executive":    executive,
        "orchestrator": orchestrator,
    }


async def _start_monitor(registry) -> None:
    """Kick off background resource watching after a short delay."""
    await asyncio.sleep(5)
    mon = registry.get("monitoring_agent")
    if mon:
        mon.set_registry(registry)
        from agents.base_agent import Task
        await mon.assign_task(Task(
            name="watch_resource",
            payload={"action": "watch_resource", "params": {"interval_seconds": 60}},
        ))


# ── CLI mode ──────────────────────────────────────────────────────────────────

async def cli_loop(ctx: dict) -> None:
    from core.events.bus import EventType
    bus      = ctx["bus"]
    settings = ctx["settings"]

    # Subscribe to SPARK responses so they print in CLI
    async def _on_response(event):
        msg = event.payload.get("message", "")
        if msg and event.payload.get("speak"):
            print(f"\nSPARK: {msg}\n")
    bus.subscribe("agent.status_update", _on_response)

    print("\n" + "=" * 62)
    print(f"  {settings.spark.name}  v{settings.spark.version}")
    print("=" * 62)
    print(ctx["executive"].get_greeting())
    print("\nCommands: research / code / document / screenshot / agents / quit")
    print("-" * 62)

    while True:
        try:
            user_input = await asyncio.get_event_loop().run_in_executor(
                None, lambda: input("\nYou: ").strip()
            )
            if not user_input:
                continue

            low = user_input.lower()

            if low in ("quit", "exit", "bye"):
                print("SPARK: Goodbye, Vayala. See you soon.")
                break

            if low == "help":
                print("""
  Natural language:   just type your request
  agents              show all agent statuses
  health              show system health
  history             show last 10 events
  quit                shutdown SPARK
""")
                continue

            if low == "agents":
                for s in ctx["registry"].get_all_statuses():
                    print(f"  {s['name']:30} [{s['status']:20}]  queue={s['queue_size']}")
                continue

            if low == "health":
                mon = ctx["registry"].get("monitoring_agent")
                if mon:
                    from agents.base_agent import Task
                    t = Task(name="system_health",
                             payload={"action": "system_health", "params": {}})
                    await mon.assign_task(t)
                    await asyncio.sleep(2)
                continue

            if low == "history":
                for e in ctx["bus"].get_history(limit=10):
                    etype = e.type if isinstance(e.type, str) else e.type.value
                    print(f"  [{e.timestamp.strftime('%H:%M:%S')}] {etype:35} src={e.source}")
                continue

            await bus.emit(EventType.USER_COMMAND, {"text": user_input}, source="cli")
            await asyncio.sleep(3)

        except (KeyboardInterrupt, EOFError):
            break

    # Shutdown
    await ctx["registry"].stop_all()
    ctx["bus"].stop()
    ctx["memory"].long_term.close()
    logger.info("SPARK shutdown complete.")


# ── API mode ──────────────────────────────────────────────────────────────────

async def start_api(ctx: dict, host: str = "0.0.0.0", port: int = 8765) -> None:
    import uvicorn
    from api.server import app, set_context
    set_context(ctx)
    config = uvicorn.Config(app, host=host, port=port, log_level="info")
    server = uvicorn.Server(config)
    logger.info(f"API server starting on http://{host}:{port}  (docs: /docs)")
    await server.serve()


# ── Admin check ───────────────────────────────────────────────────────────────

def check_admin() -> None:
    if sys.platform == "win32":
        try:
            import ctypes
            if ctypes.windll.shell32.IsUserAnAdmin():
                logger.info("  [OK] Running as Administrator")
            else:
                logger.warning("  [  ] Not Administrator - some features limited. "
                               "Right-click spark.bat -> Run as Administrator.")
        except Exception:
            pass


# ── Entry point ───────────────────────────────────────────────────────────────

def main() -> None:
    check_admin()

    args  = sys.argv[1:]
    mode  = "ui"
    if "--cli" in args:
        mode = "cli"
    elif "--api" in args:
        mode = "api"
    elif "--all" in args:
        mode = "all"

    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)

    try:
        ctx = loop.run_until_complete(boot_subsystems())
    except Exception as e:
        logger.error(f"Boot failed: {e}", exc_info=True)
        sys.exit(1)

    # ── UI mode (Electron) ──────────────────────────────────────────────────
    if mode == "ui":
        try:
            import subprocess
            import threading

            def _run_event_loop():
                asyncio.set_event_loop(loop)
                loop.run_forever()

            thread = threading.Thread(target=_run_event_loop, daemon=True)
            thread.start()

            # Start API server in background for Electron to connect
            api_task = loop.create_task(start_api(ctx, host="127.0.0.1", port=8765))

            # Start Electron
            logger.info("Launching Electron UI...")
            frontend_dir = ROOT / "frontend"
            
            # Use npm run dev if in development, otherwise start built app
            cmd = "npm run dev"
            
            process = subprocess.Popen(
                cmd,
                shell=True,
                cwd=str(frontend_dir)
            )
            process.wait()

            loop.call_soon_threadsafe(loop.stop)
            sys.exit(process.returncode)
        except Exception as e:
            logger.error(f"Failed to launch UI: {e}")
            mode = "cli"

    # ── CLI mode ───────────────────────────────────────────────────────────
    if mode == "cli":
        try:
            loop.run_until_complete(cli_loop(ctx))
        except KeyboardInterrupt:
            pass
        finally:
            loop.close()
        return

    # ── API mode ───────────────────────────────────────────────────────────
    if mode == "api":
        try:
            loop.run_until_complete(start_api(ctx))
        except KeyboardInterrupt:
            pass
        finally:
            loop.close()
        return

    # ── All mode (CLI + API) ───────────────────────────────────────────────
    if mode == "all":
        async def _combined():
            await asyncio.gather(
                cli_loop(ctx),
                start_api(ctx),
            )
        try:
            loop.run_until_complete(_combined())
        except KeyboardInterrupt:
            pass
        finally:
            loop.close()


if __name__ == "__main__":
    main()
