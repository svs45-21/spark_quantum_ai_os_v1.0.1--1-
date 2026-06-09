"""
SPARK QUANTUM AI OS — Desktop Control Agent
Phase 4: Full computer control — open/close apps, keyboard, mouse,
window management, file management, system commands.
Every destructive action requires approval.
"""
from __future__ import annotations

import asyncio
import logging
import os
import subprocess
import sys
import time
from pathlib import Path
from typing import Any, Dict, List, Optional, Tuple

from agents.base_agent import AgentCapability, BaseAgent, Task, TaskPriority
from core.events.bus import EventBus, EventType
from security.security_manager import Permission, get_security

logger = logging.getLogger("spark.desktop")


class DesktopController:
    """
    Low-level desktop control primitives.
    All methods are synchronous — call via run_in_executor.
    """

    def __init__(self, action_delay_ms: int = 300) -> None:
        self._delay = action_delay_ms / 1000.0
        self._available = False
        self._screenshot_available = False
        self._init()

    def _init(self) -> None:
        try:
            import pyautogui
            pyautogui.FAILSAFE = True       # Move mouse to corner to abort
            pyautogui.PAUSE = self._delay
            self._gui = pyautogui
            self._available = True
            logger.info("PyAutoGUI initialized")
        except ImportError:
            logger.warning("pyautogui not installed — desktop control disabled")

        try:
            import mss
            self._mss = mss
            self._screenshot_available = True
        except ImportError:
            try:
                from PIL import ImageGrab
                self._imagegrab = ImageGrab
                self._screenshot_available = True
            except ImportError:
                logger.warning("No screenshot library available")

    # ── Screenshots ──────────────────────────────────────────────────────────

    def screenshot(self, save_path: str = None) -> Optional[bytes]:
        """Capture entire screen. Returns PNG bytes."""
        try:
            if hasattr(self, "_mss"):
                import mss.tools
                with self._mss.mss() as sct:
                    monitor = sct.monitors[0]
                    img = sct.grab(monitor)
                    png = mss.tools.to_png(img.rgb, img.size)
                    if save_path:
                        Path(save_path).write_bytes(png)
                    return png
            elif hasattr(self, "_imagegrab"):
                img = self._imagegrab.grab()
                buf = io.BytesIO()
                img.save(buf, format="PNG")
                data = buf.getvalue()
                if save_path:
                    Path(save_path).write_bytes(data)
                return data
        except Exception as e:
            logger.error(f"Screenshot failed: {e}")
        return None

    # ── Keyboard ─────────────────────────────────────────────────────────────

    def type_text(self, text: str, interval: float = 0.02) -> bool:
        if not self._available:
            return False
        try:
            self._gui.typewrite(text, interval=interval)
            return True
        except Exception as e:
            logger.error(f"type_text failed: {e}")
            return False

    def press_key(self, key: str) -> bool:
        if not self._available:
            return False
        try:
            self._gui.press(key)
            return True
        except Exception as e:
            logger.error(f"press_key failed: {e}")
            return False

    def hotkey(self, *keys: str) -> bool:
        if not self._available:
            return False
        try:
            self._gui.hotkey(*keys)
            return True
        except Exception as e:
            logger.error(f"hotkey failed: {e}")
            return False

    # ── Mouse ─────────────────────────────────────────────────────────────────

    def click(self, x: int, y: int, button: str = "left", clicks: int = 1) -> bool:
        if not self._available:
            return False
        try:
            self._gui.click(x, y, button=button, clicks=clicks)
            return True
        except Exception as e:
            logger.error(f"click failed: {e}")
            return False

    def move(self, x: int, y: int, duration: float = 0.3) -> bool:
        if not self._available:
            return False
        try:
            self._gui.moveTo(x, y, duration=duration)
            return True
        except Exception as e:
            logger.error(f"move failed: {e}")
            return False

    def scroll(self, clicks: int, x: int = None, y: int = None) -> bool:
        if not self._available:
            return False
        try:
            self._gui.scroll(clicks, x=x, y=y)
            return True
        except Exception as e:
            logger.error(f"scroll failed: {e}")
            return False

    def drag(self, x1: int, y1: int, x2: int, y2: int, duration: float = 0.5) -> bool:
        if not self._available:
            return False
        try:
            self._gui.moveTo(x1, y1)
            self._gui.dragTo(x2, y2, duration=duration, button="left")
            return True
        except Exception as e:
            logger.error(f"drag failed: {e}")
            return False

    # ── Application Management ────────────────────────────────────────────────

    def open_application(self, app_name: str) -> bool:
        """Open an application by name (Windows)."""
        try:
            if sys.platform == "win32":
                os.startfile(app_name)
            elif sys.platform == "darwin":
                subprocess.Popen(["open", "-a", app_name])
            else:
                subprocess.Popen([app_name])
            logger.info(f"Opened: {app_name}")
            return True
        except Exception as e:
            logger.error(f"open_application failed: {e}")
            return False

    def open_url(self, url: str) -> bool:
        """Open URL in default browser."""
        try:
            import webbrowser
            webbrowser.open(url)
            return True
        except Exception as e:
            logger.error(f"open_url failed: {e}")
            return False

    def open_file(self, path: str) -> bool:
        """Open file with default application."""
        try:
            if sys.platform == "win32":
                os.startfile(path)
            elif sys.platform == "darwin":
                subprocess.Popen(["open", path])
            else:
                subprocess.Popen(["xdg-open", path])
            return True
        except Exception as e:
            logger.error(f"open_file failed: {e}")
            return False

    def list_running_apps(self) -> List[Dict]:
        """List all running processes."""
        try:
            import psutil
            apps = []
            for proc in psutil.process_iter(["pid", "name", "status"]):
                try:
                    apps.append(proc.info)
                except (psutil.NoSuchProcess, psutil.AccessDenied):
                    pass
            return apps
        except ImportError:
            return []

    def kill_process(self, pid: int = None, name: str = None) -> bool:
        """Kill a process by PID or name."""
        try:
            import psutil
            if pid:
                psutil.Process(pid).terminate()
                return True
            if name:
                for proc in psutil.process_iter(["pid", "name"]):
                    if proc.info["name"].lower() == name.lower():
                        proc.terminate()
                        return True
        except Exception as e:
            logger.error(f"kill_process failed: {e}")
        return False

    # ── System Commands ───────────────────────────────────────────────────────

    def system_shutdown(self) -> None:
        if sys.platform == "win32":
            os.system("shutdown /s /t 10")
        else:
            os.system("shutdown -h now")

    def system_restart(self) -> None:
        if sys.platform == "win32":
            os.system("shutdown /r /t 10")
        else:
            os.system("shutdown -r now")

    def system_sleep(self) -> None:
        if sys.platform == "win32":
            os.system("rundll32.exe powrprof.dll,SetSuspendState 0,1,0")

    def system_lock(self) -> None:
        if sys.platform == "win32":
            import ctypes
            ctypes.windll.user32.LockWorkStation()

    def set_volume(self, level: int) -> bool:
        """Set system volume (0-100). Windows only."""
        if sys.platform != "win32":
            return False
        try:
            from ctypes import cast, POINTER
            from comtypes import CLSCTX_ALL
            from pycaw.pycaw import AudioUtilities, IAudioEndpointVolume
            devices = AudioUtilities.GetSpeakers()
            interface = devices.Activate(IAudioEndpointVolume._iid_, CLSCTX_ALL, None)
            volume = cast(interface, POINTER(IAudioEndpointVolume))
            scalar = max(0.0, min(1.0, level / 100.0))
            volume.SetMasterVolumeLevelScalar(scalar, None)
            return True
        except Exception as e:
            logger.warning(f"set_volume failed: {e}")
            return False

    # ── Clipboard ─────────────────────────────────────────────────────────────

    def copy_to_clipboard(self, text: str) -> bool:
        if not self._available:
            return False
        try:
            self._gui.hotkey("ctrl", "a")
            import pyperclip
            pyperclip.copy(text)
            return True
        except Exception as e:
            logger.error(f"copy_to_clipboard failed: {e}")
            return False

    def get_clipboard(self) -> str:
        try:
            import pyperclip
            return pyperclip.paste()
        except Exception:
            return ""

    # ── File Operations ───────────────────────────────────────────────────────

    def list_directory(self, path: str) -> List[Dict]:
        p = Path(path)
        if not p.exists():
            return []
        return [
            {
                "name": item.name,
                "type": "dir" if item.is_dir() else "file",
                "size": item.stat().st_size if item.is_file() else 0,
                "modified": item.stat().st_mtime,
            }
            for item in sorted(p.iterdir())
        ]

    def read_file(self, path: str, max_bytes: int = 1_000_000) -> str:
        try:
            return Path(path).read_text(encoding="utf-8", errors="replace")[:max_bytes]
        except Exception as e:
            logger.error(f"read_file failed: {e}")
            return ""

    def write_file(self, path: str, content: str) -> bool:
        try:
            p = Path(path)
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_text(content, encoding="utf-8")
            return True
        except Exception as e:
            logger.error(f"write_file failed: {e}")
            return False

    def delete_file(self, path: str) -> bool:
        try:
            Path(path).unlink()
            return True
        except Exception as e:
            logger.error(f"delete_file failed: {e}")
            return False

    def run_command(self, command: str, shell: bool = True, timeout: int = 30) -> Tuple[int, str, str]:
        """Run a shell command. Returns (returncode, stdout, stderr)."""
        try:
            result = subprocess.run(
                command, shell=shell, capture_output=True,
                text=True, timeout=timeout
            )
            return result.returncode, result.stdout, result.stderr
        except subprocess.TimeoutExpired:
            return -1, "", "Timeout"
        except Exception as e:
            return -1, "", str(e)


# ── Desktop Agent ──────────────────────────────────────────────────────────────

class DesktopAgent(BaseAgent):
    name = "desktop_agent"
    description = "Controls the computer: apps, keyboard, mouse, files, system."
    capabilities = [
        AgentCapability("screenshot", "Take a screenshot"),
        AgentCapability("open_app", "Open an application"),
        AgentCapability("type_text", "Type text on keyboard"),
        AgentCapability("click", "Click at screen coordinates"),
        AgentCapability("list_files", "List files in a directory"),
        AgentCapability("read_file", "Read a file"),
        AgentCapability("write_file", "Write content to a file"),
        AgentCapability("run_command", "Execute a shell command", requires_approval=True),
        AgentCapability("delete_file", "Delete a file", requires_approval=True),
        AgentCapability("shutdown", "Shutdown the system", requires_approval=True),
    ]

    def __init__(self, bus: Optional[EventBus] = None, settings=None) -> None:
        super().__init__(bus)
        from core.settings import get_settings
        cfg = settings or get_settings()
        self._ctrl = DesktopController(cfg.desktop.action_delay_ms)
        self._security = get_security()

    async def execute(self, task: Task) -> Any:
        action = task.payload.get("action", "")
        params = task.payload.get("params", {})

        # Emit action event
        await self._bus.emit(
            EventType.DESKTOP_ACTION,
            {"action": action, "params": params},
            source=self.name,
        )

        loop = asyncio.get_event_loop()
        result = None

        if action == "screenshot":
            save_path = params.get("save_path")
            result = await loop.run_in_executor(None, self._ctrl.screenshot, save_path)
            logger.info(f"Screenshot captured: {len(result) if result else 0} bytes")

        elif action == "open_app":
            app = params.get("app", "")
            result = await loop.run_in_executor(None, self._ctrl.open_application, app)

        elif action == "open_url":
            url = params.get("url", "")
            result = await loop.run_in_executor(None, self._ctrl.open_url, url)

        elif action == "open_file":
            path = params.get("path", "")
            result = await loop.run_in_executor(None, self._ctrl.open_file, path)

        elif action == "type_text":
            text = params.get("text", "")
            result = await loop.run_in_executor(None, self._ctrl.type_text, text)

        elif action == "press_key":
            key = params.get("key", "")
            result = await loop.run_in_executor(None, self._ctrl.press_key, key)

        elif action == "hotkey":
            keys = params.get("keys", [])
            result = await loop.run_in_executor(None, lambda: self._ctrl.hotkey(*keys))

        elif action == "click":
            x, y = params.get("x", 0), params.get("y", 0)
            button = params.get("button", "left")
            result = await loop.run_in_executor(None, self._ctrl.click, x, y, button)

        elif action == "scroll":
            result = await loop.run_in_executor(
                None, self._ctrl.scroll, params.get("clicks", 3), params.get("x"), params.get("y")
            )

        elif action == "list_files":
            path = params.get("path", ".")
            result = await loop.run_in_executor(None, self._ctrl.list_directory, path)

        elif action == "read_file":
            path = params.get("path", "")
            result = await loop.run_in_executor(None, self._ctrl.read_file, path)

        elif action == "write_file":
            path = params.get("path", "")
            content = params.get("content", "")
            result = await loop.run_in_executor(None, self._ctrl.write_file, path, content)

        elif action == "delete_file":
            path = params.get("path", "")
            result = await loop.run_in_executor(None, self._ctrl.delete_file, path)

        elif action == "run_command":
            cmd = params.get("command", "")
            code, out, err = await loop.run_in_executor(None, self._ctrl.run_command, cmd)
            result = {"returncode": code, "stdout": out, "stderr": err}

        elif action == "list_processes":
            result = await loop.run_in_executor(None, self._ctrl.list_running_apps)

        elif action == "volume":
            level = params.get("level", 50)
            result = await loop.run_in_executor(None, self._ctrl.set_volume, level)

        elif action == "shutdown":
            await loop.run_in_executor(None, self._ctrl.system_shutdown)
            result = True

        elif action == "restart":
            await loop.run_in_executor(None, self._ctrl.system_restart)
            result = True

        elif action == "sleep":
            await loop.run_in_executor(None, self._ctrl.system_sleep)
            result = True

        elif action == "lock":
            await loop.run_in_executor(None, self._ctrl.system_lock)
            result = True

        else:
            raise ValueError(f"Unknown desktop action: {action}")

        await self._bus.emit(
            EventType.DESKTOP_ACTION_DONE,
            {"action": action, "success": result is not None},
            source=self.name,
        )
        return result
