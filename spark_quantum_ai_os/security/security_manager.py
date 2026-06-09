"""
SPARK QUANTUM AI OS — Security Module
RBAC, audit logging, tool permissions, approval workflows.
"""
from __future__ import annotations

import json
import logging
import os
import sqlite3
from datetime import datetime
from enum import Enum
from pathlib import Path
from typing import Dict, List, Optional, Set
from uuid import uuid4

logger = logging.getLogger("spark.security")


class Permission(str, Enum):
    # File system
    FILE_READ = "file_read"
    FILE_WRITE = "file_write"
    FILE_DELETE = "file_delete"

    # Network
    BROWSER_BROWSE = "browser_browse"
    BROWSER_FORM_SUBMIT = "browser_form_submit"
    BROWSER_DOWNLOAD = "browser_download"

    # Desktop
    DESKTOP_SCREENSHOT = "desktop_screenshot"
    DESKTOP_KEYBOARD = "desktop_keyboard"
    DESKTOP_MOUSE = "desktop_mouse"
    DESKTOP_APP_OPEN = "desktop_app_open"
    DESKTOP_APP_CLOSE = "desktop_app_close"

    # System
    EXECUTE_CODE = "execute_code"
    SYSTEM_SHUTDOWN = "system_shutdown"
    SYSTEM_RESTART = "system_restart"

    # Communication
    SEND_MESSAGE = "send_message"
    READ_MESSAGE = "read_message"

    # Memory
    MEMORY_READ = "memory_read"
    MEMORY_WRITE = "memory_write"


class Role(str, Enum):
    ADMIN = "admin"
    USER = "user"
    GUEST = "guest"
    AGENT = "agent"


ROLE_PERMISSIONS: Dict[Role, Set[Permission]] = {
    Role.ADMIN: set(Permission),  # All permissions
    Role.USER: {
        Permission.FILE_READ, Permission.FILE_WRITE,
        Permission.BROWSER_BROWSE, Permission.BROWSER_DOWNLOAD,
        Permission.DESKTOP_SCREENSHOT, Permission.DESKTOP_APP_OPEN,
        Permission.EXECUTE_CODE, Permission.MEMORY_READ, Permission.MEMORY_WRITE,
        Permission.READ_MESSAGE,
    },
    Role.GUEST: {
        Permission.FILE_READ, Permission.BROWSER_BROWSE,
        Permission.DESKTOP_SCREENSHOT, Permission.MEMORY_READ,
    },
    Role.AGENT: {
        Permission.FILE_READ, Permission.FILE_WRITE,
        Permission.BROWSER_BROWSE, Permission.DESKTOP_SCREENSHOT,
        Permission.MEMORY_READ, Permission.MEMORY_WRITE,
        Permission.EXECUTE_CODE,
    },
}

# Actions requiring explicit user approval even for admins
APPROVAL_REQUIRED: Set[Permission] = {
    Permission.FILE_DELETE,
    Permission.BROWSER_FORM_SUBMIT,
    Permission.EXECUTE_CODE,
    Permission.SYSTEM_SHUTDOWN,
    Permission.SYSTEM_RESTART,
    Permission.SEND_MESSAGE,
}


class AuditLog:
    """Persistent audit trail of all security-relevant actions."""

    def __init__(self, db_path: str = "logs/audit.db") -> None:
        Path(db_path).parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(db_path, check_same_thread=False)
        self._init()

    def _init(self) -> None:
        self._conn.execute("""
            CREATE TABLE IF NOT EXISTS audit_log (
                id TEXT PRIMARY KEY,
                timestamp TEXT NOT NULL,
                actor TEXT NOT NULL,
                action TEXT NOT NULL,
                resource TEXT,
                permission TEXT,
                granted INTEGER NOT NULL,
                reason TEXT,
                metadata TEXT DEFAULT '{}'
            )
        """)
        self._conn.execute("CREATE INDEX IF NOT EXISTS idx_ts ON audit_log(timestamp)")
        self._conn.execute("CREATE INDEX IF NOT EXISTS idx_actor ON audit_log(actor)")
        self._conn.commit()

    def log(
        self,
        actor: str,
        action: str,
        permission: Permission,
        granted: bool,
        resource: str = None,
        reason: str = None,
        metadata: dict = None,
    ) -> None:
        self._conn.execute(
            """INSERT INTO audit_log
               (id, timestamp, actor, action, resource, permission, granted, reason, metadata)
               VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""",
            (
                str(uuid4()),
                datetime.utcnow().isoformat(),
                actor,
                action,
                resource,
                permission.value,
                int(granted),
                reason,
                json.dumps(metadata or {}),
            ),
        )
        self._conn.commit()

        level = logging.INFO if granted else logging.WARNING
        logger.log(level, f"AUDIT | {actor} | {action} | {permission.value} | {'GRANTED' if granted else 'DENIED'}")

    def get_recent(self, limit: int = 100) -> List[Dict]:
        rows = self._conn.execute(
            "SELECT timestamp, actor, action, permission, granted, reason FROM audit_log "
            "ORDER BY timestamp DESC LIMIT ?",
            (limit,),
        ).fetchall()
        return [
            {
                "timestamp": r[0], "actor": r[1], "action": r[2],
                "permission": r[3], "granted": bool(r[4]), "reason": r[5],
            }
            for r in rows
        ]


class SecurityManager:
    """
    Central security manager.
    Checks permissions, logs all actions, manages approval flow.
    """

    def __init__(self, settings=None) -> None:
        from core.settings import get_settings
        cfg = settings or get_settings()
        self._audit = AuditLog(cfg.security.audit_log.replace(".log", ".db"))
        self._actor_roles: Dict[str, Role] = {}
        # Default: primary user is admin
        self._actor_roles[cfg.spark.user.name] = Role.ADMIN
        self._actor_roles["executive_agent"] = Role.AGENT
        self._actor_roles["system"] = Role.ADMIN

    def set_role(self, actor: str, role: Role) -> None:
        self._actor_roles[actor] = role

    def get_role(self, actor: str) -> Role:
        return self._actor_roles.get(actor, Role.GUEST)

    def check_permission(self, actor: str, permission: Permission,
                         resource: str = None, action: str = None) -> bool:
        role = self.get_role(actor)
        allowed_perms = ROLE_PERMISSIONS.get(role, set())
        granted = permission in allowed_perms

        self._audit.log(
            actor=actor,
            action=action or permission.value,
            permission=permission,
            granted=granted,
            resource=resource,
            reason=f"Role: {role.value}",
        )
        return granted

    def requires_approval(self, permission: Permission) -> bool:
        return permission in APPROVAL_REQUIRED

    def get_audit_log(self, limit: int = 100) -> List[Dict]:
        return self._audit.get_recent(limit)


_security: Optional[SecurityManager] = None


def get_security() -> SecurityManager:
    global _security
    if _security is None:
        _security = SecurityManager()
    return _security
