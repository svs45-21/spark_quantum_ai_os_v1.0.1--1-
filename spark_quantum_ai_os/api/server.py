"""
SPARK QUANTUM AI OS — FastAPI Backend Server
REST + WebSocket API for external control, integrations, and web UI.
Run standalone:  uvicorn api.server:app --host 0.0.0.0 --port 8765
Or embedded via main.py with --api flag.
"""
from __future__ import annotations

import asyncio
import json
import logging
from datetime import datetime
from typing import Any, Dict, List, Optional

from fastapi import FastAPI, HTTPException, WebSocket, WebSocketDisconnect
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse
from pydantic import BaseModel

logger = logging.getLogger("spark.api")

# ── App ───────────────────────────────────────────────────────────────────────

app = FastAPI(
    title="SPARK QUANTUM AI OS",
    description="REST + WebSocket API for SPARK AI Operating System",
    version="1.0.1",
    docs_url="/docs",
    redoc_url="/redoc",
)

app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

# Global context injected by main.py
_ctx: Optional[Dict] = None
_ws_clients: List[WebSocket] = []


def set_context(ctx: Dict) -> None:
    global _ctx
    _ctx = ctx


def _require_ctx():
    if not _ctx:
        raise HTTPException(status_code=503, detail="SPARK context not initialised")
    return _ctx


# ── Request / Response models ─────────────────────────────────────────────────

class CommandRequest(BaseModel):
    text: str
    source: str = "api"


class TaskRequest(BaseModel):
    agent: str
    action: str
    params: Dict[str, Any] = {}
    requires_approval: bool = False


class ReminderRequest(BaseModel):
    text: str
    minutes: int = 0
    hours: int = 0
    at: Optional[str] = None      # ISO datetime


class MemoryStoreRequest(BaseModel):
    key: str
    value: Any
    category: str = "general"
    importance: int = 5


# ── Health & Info ─────────────────────────────────────────────────────────────

@app.get("/", tags=["System"])
async def root():
    return {
        "name": "SPARK QUANTUM AI OS",
        "version": "1.0.1",
        "status": "online",
        "timestamp": datetime.utcnow().isoformat(),
    }


@app.get("/health", tags=["System"])
async def health():
    ctx = _require_ctx()
    registry = ctx["registry"]
    return {
        "status": "healthy",
        "agents": len(registry),
        "agent_statuses": registry.get_all_statuses(),
        "timestamp": datetime.utcnow().isoformat(),
    }


@app.get("/settings", tags=["System"])
async def get_settings():
    ctx = _require_ctx()
    cfg = ctx["settings"]
    return {
        "name":      cfg.spark.name,
        "version":   cfg.spark.version,
        "user":      cfg.spark.user.name,
        "nickname":  cfg.spark.user.nickname,
        "wake_word": cfg.spark.wake_word,
        "model_providers": ctx["router"].get_available_providers(),
    }


# ── Commands ──────────────────────────────────────────────────────────────────

@app.post("/command", tags=["Commands"])
async def send_command(req: CommandRequest):
    ctx = _require_ctx()
    from core.events.bus import EventType
    event = await ctx["bus"].emit(
        EventType.USER_COMMAND,
        {"text": req.text},
        source=req.source,
    )
    await _broadcast_ws({"type": "command", "text": req.text, "event_id": event.id})
    return {"status": "dispatched", "event_id": event.id, "text": req.text}


@app.post("/task", tags=["Commands"])
async def dispatch_task(req: TaskRequest):
    ctx = _require_ctx()
    from agents.base_agent import Task, TaskPriority
    registry = ctx["registry"]
    agent = registry.get(req.agent)
    if not agent:
        raise HTTPException(status_code=404, detail=f"Agent not found: {req.agent}")

    task = Task(
        name=req.action,
        description=f"API task: {req.action}",
        payload={"action": req.action, "params": req.params},
        requires_approval=req.requires_approval,
    )
    await agent.assign_task(task)
    return {"status": "assigned", "task_id": task.id, "agent": req.agent}


# ── Agents ────────────────────────────────────────────────────────────────────

@app.get("/agents", tags=["Agents"])
async def list_agents():
    ctx = _require_ctx()
    return ctx["registry"].get_all_statuses()


@app.get("/agents/{agent_name}", tags=["Agents"])
async def get_agent(agent_name: str):
    ctx = _require_ctx()
    agent = ctx["registry"].get(agent_name)
    if not agent:
        raise HTTPException(status_code=404, detail=f"Agent not found: {agent_name}")
    return agent.get_status_dict()


# ── Memory ────────────────────────────────────────────────────────────────────

@app.post("/memory", tags=["Memory"])
async def store_memory(req: MemoryStoreRequest):
    ctx = _require_ctx()
    ctx["memory"].remember(req.key, req.value, req.category, req.importance, source="api")
    return {"status": "stored", "key": req.key}


@app.get("/memory/{key}", tags=["Memory"])
async def get_memory(key: str):
    ctx = _require_ctx()
    value = ctx["memory"].recall(key)
    if value is None:
        raise HTTPException(status_code=404, detail=f"Key not found: {key}")
    return {"key": key, "value": value}


@app.get("/memory/search/{query}", tags=["Memory"])
async def search_memory(query: str, semantic: bool = True):
    ctx = _require_ctx()
    results = ctx["memory"].search(query, semantic=semantic)
    return {"query": query, "results": results}


# ── Research ──────────────────────────────────────────────────────────────────

@app.get("/research/search", tags=["Research"])
async def search_papers(q: str, limit: int = 10):
    ctx = _require_ctx()
    from agents.base_agent import Task
    agent = ctx["registry"].get("research_agent")
    if not agent:
        raise HTTPException(status_code=503, detail="Research agent not available")
    task = Task(
        name="search_papers",
        payload={"action": "search_papers", "params": {"query": q, "limit": limit}},
    )
    await agent.assign_task(task)
    return {"status": "searching", "query": q, "task_id": task.id}


# ── Documents ─────────────────────────────────────────────────────────────────

@app.post("/documents/report", tags=["Documents"])
async def create_report(topic: str, format: str = "docx"):
    ctx = _require_ctx()
    from agents.base_agent import Task
    agent = ctx["registry"].get("document_agent")
    if not agent:
        raise HTTPException(status_code=503, detail="Document agent not available")
    task = Task(
        name="create_report",
        payload={"action": "create_report", "params": {"topic": topic, "format": format}},
    )
    await agent.assign_task(task)
    return {"status": "generating", "topic": topic, "task_id": task.id}


# ── Security / Audit ──────────────────────────────────────────────────────────

@app.get("/audit", tags=["Security"])
async def get_audit_log(limit: int = 50):
    ctx = _require_ctx()
    return ctx["security"].get_audit_log(limit=limit)


@app.post("/approve/{task_id}", tags=["Security"])
async def approve_task(task_id: str):
    ctx = _require_ctx()
    from core.events.bus import EventType
    await ctx["bus"].emit(
        EventType.USER_APPROVAL,
        {"correlation_id": task_id},
        source="api",
    )
    return {"status": "approved", "task_id": task_id}


@app.post("/deny/{task_id}", tags=["Security"])
async def deny_task(task_id: str):
    ctx = _require_ctx()
    from core.events.bus import EventType
    await ctx["bus"].emit(
        EventType.USER_DENIAL,
        {"correlation_id": task_id},
        source="api",
    )
    return {"status": "denied", "task_id": task_id}


# ── WebSocket ─────────────────────────────────────────────────────────────────

@app.websocket("/ws")
async def websocket_endpoint(websocket: WebSocket):
    await websocket.accept()
    _ws_clients.append(websocket)
    logger.info(f"WebSocket client connected. Total: {len(_ws_clients)}")

    # Wire event bus → websocket
    ctx = _ctx
    if ctx:
        async def _on_event(event):
            await _broadcast_ws({
                "type":    event.type if isinstance(event.type, str) else event.type.value,
                "source":  event.source,
                "payload": event.payload,
                "time":    event.timestamp.isoformat(),
            })

        ctx["bus"].subscribe("*", _on_event)

    try:
        while True:
            raw = await websocket.receive_text()
            try:
                msg = json.loads(raw)
                if msg.get("type") == "command" and ctx:
                    from core.events.bus import EventType
                    await ctx["bus"].emit(
                        EventType.USER_COMMAND,
                        {"text": msg.get("text", "")},
                        source="websocket",
                    )
            except json.JSONDecodeError:
                pass
    except WebSocketDisconnect:
        _ws_clients.remove(websocket)
        logger.info(f"WebSocket client disconnected. Remaining: {len(_ws_clients)}")


async def _broadcast_ws(data: Dict) -> None:
    if not _ws_clients:
        return
    msg = json.dumps(data)
    dead = []
    for ws in _ws_clients:
        try:
            await ws.send_text(msg)
        except Exception:
            dead.append(ws)
    for ws in dead:
        if ws in _ws_clients:
            _ws_clients.remove(ws)


# ── Event log endpoint ────────────────────────────────────────────────────────

@app.get("/events", tags=["System"])
async def get_event_history(limit: int = 50):
    ctx = _require_ctx()
    history = ctx["bus"].get_history(limit=limit)
    return [
        {
            "id":        e.id,
            "type":      e.type if isinstance(e.type, str) else e.type.value,
            "source":    e.source,
            "payload":   e.payload,
            "timestamp": e.timestamp.isoformat(),
        }
        for e in history
    ]
