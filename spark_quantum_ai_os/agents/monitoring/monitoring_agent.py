"""
SPARK QUANTUM AI OS — Monitoring Agent
Watches: system resources, agent health, task queues, error rates, disk space.
Fires alerts via event bus. Produces health reports.
"""
from __future__ import annotations

import asyncio
import logging
import os
import platform
from datetime import datetime
from typing import Any, Dict, List, Optional

from agents.base_agent import AgentCapability, BaseAgent, Task
from core.events.bus import EventBus, EventType

logger = logging.getLogger("spark.monitoring")


class SystemMonitor:
    """Low-level system metrics via psutil."""

    def collect(self) -> Dict:
        try:
            import psutil
            cpu     = psutil.cpu_percent(interval=0.5)
            mem     = psutil.virtual_memory()
            disk    = psutil.disk_usage(os.getcwd())
            boot    = datetime.fromtimestamp(psutil.boot_time())
            uptime  = (datetime.now() - boot).total_seconds()
            net     = psutil.net_io_counters()

            return {
                "cpu_percent":      cpu,
                "ram_total_gb":     round(mem.total / 1e9, 2),
                "ram_used_gb":      round(mem.used  / 1e9, 2),
                "ram_percent":      mem.percent,
                "disk_total_gb":    round(disk.total / 1e9, 2),
                "disk_used_gb":     round(disk.used  / 1e9, 2),
                "disk_percent":     disk.percent,
                "uptime_hours":     round(uptime / 3600, 1),
                "net_sent_mb":      round(net.bytes_sent / 1e6, 2),
                "net_recv_mb":      round(net.bytes_recv / 1e6, 2),
                "platform":         platform.system(),
                "python":           platform.python_version(),
                "timestamp":        datetime.utcnow().isoformat(),
            }
        except ImportError:
            return {"error": "psutil not installed", "timestamp": datetime.utcnow().isoformat()}
        except Exception as e:
            return {"error": str(e), "timestamp": datetime.utcnow().isoformat()}


class MonitoringAgent(BaseAgent):
    name = "monitoring_agent"
    description = "Monitors system health, agent status, resources, and fires alerts."
    capabilities = [
        AgentCapability("system_health",   "Get full system health report"),
        AgentCapability("agent_health",    "Get status of all SPARK agents"),
        AgentCapability("watch_resource",  "Start continuous resource watching"),
        AgentCapability("stop_watch",      "Stop continuous resource watching"),
        AgentCapability("get_logs",        "Get recent log entries"),
        AgentCapability("alert_thresholds","Configure alert thresholds"),
    ]

    # Default alert thresholds
    THRESHOLDS = {
        "cpu_percent":  85.0,
        "ram_percent":  90.0,
        "disk_percent": 90.0,
    }

    def __init__(self, bus: Optional[EventBus] = None, registry=None) -> None:
        super().__init__(bus)
        self._sys      = SystemMonitor()
        self._registry = registry        # injected after boot
        self._watching = False
        self._watch_interval = 30        # seconds
        self._thresholds     = dict(self.THRESHOLDS)
        self._alert_history: List[Dict]  = []

    def set_registry(self, registry) -> None:
        self._registry = registry

    async def initialize(self) -> None:
        logger.info("Monitoring agent initialized")

    async def execute(self, task: Task) -> Any:
        action = task.payload.get("action", "")
        params = task.payload.get("params", {})

        if action == "system_health":
            return await self._system_health()
        elif action == "agent_health":
            return self._agent_health()
        elif action == "watch_resource":
            interval = params.get("interval_seconds", 30)
            asyncio.create_task(self._watch_loop(interval))
            return {"status": "watching", "interval_seconds": interval}
        elif action == "stop_watch":
            self._watching = False
            return {"status": "stopped"}
        elif action == "get_logs":
            return self._get_logs(params.get("lines", 50))
        elif action == "alert_thresholds":
            self._thresholds.update(params)
            return {"thresholds": self._thresholds}
        else:
            raise ValueError(f"Unknown monitoring action: {action}")

    async def _system_health(self) -> Dict:
        loop   = asyncio.get_event_loop()
        metrics = await loop.run_in_executor(None, self._sys.collect)
        alerts  = self._check_alerts(metrics)
        return {
            "metrics":       metrics,
            "alerts":        alerts,
            "agent_status":  self._agent_health(),
            "spark_version": "1.0.1",
        }

    def _agent_health(self) -> List[Dict]:
        if not self._registry:
            return []
        return self._registry.get_all_statuses()

    def _check_alerts(self, metrics: Dict) -> List[str]:
        alerts = []
        for key, threshold in self._thresholds.items():
            val = metrics.get(key)
            if val is not None and val >= threshold:
                msg = f"[WARN]  {key} at {val:.1f}% (threshold {threshold}%)"
                alerts.append(msg)
                self._alert_history.append({"alert": msg, "time": datetime.utcnow().isoformat()})

        return alerts

    async def _watch_loop(self, interval: int) -> None:
        self._watching = True
        logger.info(f"Resource watch started (every {interval}s)")
        while self._watching:
            loop    = asyncio.get_event_loop()
            metrics = await loop.run_in_executor(None, self._sys.collect)
            alerts  = self._check_alerts(metrics)

            if alerts:
                for alert in alerts:
                    await self._bus.emit(
                        EventType.SYSTEM_ERROR,
                        {"message": alert, "metrics": metrics},
                        source=self.name,
                    )
                    logger.warning(alert)

            await asyncio.sleep(interval)

    def _get_logs(self, lines: int) -> List[str]:
        log_path = "logs/spark.log"
        try:
            with open(log_path, encoding="utf-8") as f:
                all_lines = f.readlines()
            return [l.rstrip() for l in all_lines[-lines:]]
        except FileNotFoundError:
            return ["Log file not found"]
        except Exception as e:
            return [f"Error reading logs: {e}"]
