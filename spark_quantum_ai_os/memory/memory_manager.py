"""
SPARK QUANTUM AI OS — Memory System
Multi-layer memory: short-term (in-memory), long-term (SQLite),
vector (ChromaDB), knowledge graph.
"""
from __future__ import annotations

import json
import logging
import sqlite3
from collections import deque
from datetime import datetime
from pathlib import Path
from typing import Any, Dict, List, Optional
from uuid import uuid4

logger = logging.getLogger("spark.memory")


# ── Short-Term Memory (in-process, fast) ──────────────────────────────────────

class ShortTermMemory:
    """
    Rolling window of recent events and context.
    Stored in-process as a deque for O(1) access.
    """

    def __init__(self, max_entries: int = 200) -> None:
        self._store: deque[Dict[str, Any]] = deque(maxlen=max_entries)

    def store(self, key: str, value: Any, source: str = "system") -> str:
        entry_id = str(uuid4())
        self._store.appendleft({
            "id": entry_id,
            "key": key,
            "value": value,
            "source": source,
            "timestamp": datetime.utcnow().isoformat(),
        })
        return entry_id

    def get(self, key: str) -> Optional[Any]:
        for entry in self._store:
            if entry["key"] == key:
                return entry["value"]
        return None

    def get_recent(self, n: int = 20) -> List[Dict]:
        return list(self._store)[:n]

    def search(self, query: str) -> List[Dict]:
        q = query.lower()
        return [e for e in self._store if q in str(e.get("value", "")).lower()]

    def clear(self) -> None:
        self._store.clear()


# ── Long-Term Memory (SQLite) ──────────────────────────────────────────────────

class LongTermMemory:
    """
    Persistent structured memory in SQLite.
    Stores facts, user preferences, project data.
    """

    def __init__(self, db_path: str = "data/long_term.db") -> None:
        self._path = Path(db_path)
        self._path.parent.mkdir(parents=True, exist_ok=True)
        self._conn = sqlite3.connect(str(self._path), check_same_thread=False)
        self._init_schema()

    def _init_schema(self) -> None:
        self._conn.executescript("""
            CREATE TABLE IF NOT EXISTS memories (
                id TEXT PRIMARY KEY,
                key TEXT NOT NULL,
                value TEXT NOT NULL,
                category TEXT DEFAULT 'general',
                source TEXT DEFAULT 'system',
                importance INTEGER DEFAULT 5,
                created_at TEXT NOT NULL,
                accessed_at TEXT,
                access_count INTEGER DEFAULT 0
            );

            CREATE INDEX IF NOT EXISTS idx_key ON memories(key);
            CREATE INDEX IF NOT EXISTS idx_category ON memories(category);

            CREATE TABLE IF NOT EXISTS user_preferences (
                key TEXT PRIMARY KEY,
                value TEXT NOT NULL,
                updated_at TEXT NOT NULL
            );

            CREATE TABLE IF NOT EXISTS projects (
                id TEXT PRIMARY KEY,
                name TEXT NOT NULL,
                description TEXT,
                status TEXT DEFAULT 'active',
                created_at TEXT NOT NULL,
                updated_at TEXT NOT NULL,
                metadata TEXT DEFAULT '{}'
            );

            CREATE TABLE IF NOT EXISTS conversations (
                id TEXT PRIMARY KEY,
                session_id TEXT NOT NULL,
                role TEXT NOT NULL,
                content TEXT NOT NULL,
                timestamp TEXT NOT NULL
            );

            CREATE INDEX IF NOT EXISTS idx_conv_session ON conversations(session_id);
        """)
        self._conn.commit()

    def store(self, key: str, value: Any, category: str = "general",
              source: str = "system", importance: int = 5) -> str:
        entry_id = str(uuid4())
        now = datetime.utcnow().isoformat()
        self._conn.execute(
            """INSERT OR REPLACE INTO memories
               (id, key, value, category, source, importance, created_at)
               VALUES (?, ?, ?, ?, ?, ?, ?)""",
            (entry_id, key, json.dumps(value), category, source, importance, now),
        )
        self._conn.commit()
        return entry_id

    def get(self, key: str) -> Optional[Any]:
        row = self._conn.execute(
            "SELECT value FROM memories WHERE key = ? ORDER BY created_at DESC LIMIT 1", (key,)
        ).fetchone()
        if row:
            self._conn.execute(
                "UPDATE memories SET accessed_at = ?, access_count = access_count + 1 WHERE key = ?",
                (datetime.utcnow().isoformat(), key),
            )
            self._conn.commit()
            return json.loads(row[0])
        return None

    def search(self, query: str, category: str = None, limit: int = 20) -> List[Dict]:
        if category:
            rows = self._conn.execute(
                "SELECT id, key, value, category, importance, created_at FROM memories "
                "WHERE category = ? AND (key LIKE ? OR value LIKE ?) ORDER BY importance DESC LIMIT ?",
                (category, f"%{query}%", f"%{query}%", limit),
            ).fetchall()
        else:
            rows = self._conn.execute(
                "SELECT id, key, value, category, importance, created_at FROM memories "
                "WHERE key LIKE ? OR value LIKE ? ORDER BY importance DESC LIMIT ?",
                (f"%{query}%", f"%{query}%", limit),
            ).fetchall()
        return [
            {"id": r[0], "key": r[1], "value": json.loads(r[2]),
             "category": r[3], "importance": r[4], "created_at": r[5]}
            for r in rows
        ]

    def set_preference(self, key: str, value: Any) -> None:
        self._conn.execute(
            "INSERT OR REPLACE INTO user_preferences (key, value, updated_at) VALUES (?, ?, ?)",
            (key, json.dumps(value), datetime.utcnow().isoformat()),
        )
        self._conn.commit()

    def get_preference(self, key: str, default: Any = None) -> Any:
        row = self._conn.execute(
            "SELECT value FROM user_preferences WHERE key = ?", (key,)
        ).fetchone()
        return json.loads(row[0]) if row else default

    def add_conversation(self, session_id: str, role: str, content: str) -> str:
        entry_id = str(uuid4())
        self._conn.execute(
            "INSERT INTO conversations (id, session_id, role, content, timestamp) VALUES (?, ?, ?, ?, ?)",
            (entry_id, session_id, role, content, datetime.utcnow().isoformat()),
        )
        self._conn.commit()
        return entry_id

    def get_conversation(self, session_id: str, limit: int = 50) -> List[Dict]:
        rows = self._conn.execute(
            "SELECT role, content, timestamp FROM conversations WHERE session_id = ? ORDER BY timestamp LIMIT ?",
            (session_id, limit),
        ).fetchall()
        return [{"role": r[0], "content": r[1], "timestamp": r[2]} for r in rows]

    def close(self) -> None:
        self._conn.close()


# ── Vector Memory (ChromaDB) ───────────────────────────────────────────────────

class VectorMemory:
    """
    Semantic similarity search using ChromaDB.
    Stores and retrieves memories by meaning, not exact key.
    """

    def __init__(self, db_path: str = "data/chroma", collection_name: str = "spark_memory") -> None:
        self._available = False
        try:
            import chromadb
            from chromadb.config import Settings as ChromaSettings
            Path(db_path).mkdir(parents=True, exist_ok=True)
            self._client = chromadb.PersistentClient(path=db_path)
            self._collection = self._client.get_or_create_collection(
                name=collection_name,
                metadata={"hnsw:space": "cosine"},
            )
            self._available = True
            logger.info(f"VectorMemory initialized at {db_path}")
        except ImportError:
            logger.warning("ChromaDB not installed. Vector memory disabled. Run: pip install chromadb")
        except Exception as e:
            logger.warning(f"VectorMemory init failed: {e}")

    def store(self, text: str, metadata: Dict = None, doc_id: str = None) -> Optional[str]:
        if not self._available:
            return None
        doc_id = doc_id or str(uuid4())
        self._collection.add(
            documents=[text],
            metadatas=[metadata or {}],
            ids=[doc_id],
        )
        return doc_id

    def search(self, query: str, n_results: int = 5) -> List[Dict]:
        if not self._available:
            return []
        results = self._collection.query(query_texts=[query], n_results=n_results)
        output = []
        for doc, meta, dist in zip(
            results["documents"][0], results["metadatas"][0], results["distances"][0]
        ):
            output.append({"text": doc, "metadata": meta, "similarity": 1 - dist})
        return output

    @property
    def is_available(self) -> bool:
        return self._available


# ── Unified Memory Manager ─────────────────────────────────────────────────────

class MemoryManager:
    """
    Unified interface to all memory layers.
    """

    def __init__(self, settings=None) -> None:
        from core.settings import get_settings
        cfg = settings or get_settings()

        self.short_term = ShortTermMemory(max_entries=200)
        self.long_term = LongTermMemory(cfg.memory.long_term_db_path)
        self.vector = VectorMemory(cfg.memory.vector_db_path, cfg.memory.vector_collection)

        logger.info("MemoryManager initialized")

    def remember(self, key: str, value: Any, category: str = "general",
                 importance: int = 5, source: str = "system") -> None:
        """Store to both short-term and long-term memory."""
        self.short_term.store(key, value, source)
        self.long_term.store(key, value, category, source, importance)

        if isinstance(value, str) and self.vector.is_available:
            self.vector.store(value, {"key": key, "category": category, "source": source})

    def recall(self, key: str, prefer_recent: bool = True) -> Optional[Any]:
        """Try short-term first, fall back to long-term."""
        result = self.short_term.get(key)
        if result is not None:
            return result
        return self.long_term.get(key)

    def search(self, query: str, semantic: bool = True) -> List[Dict]:
        """Search across all memory layers."""
        results = []

        # Short-term text search
        stm = self.short_term.search(query)
        results.extend([{"source": "short_term", **r} for r in stm[:5]])

        # Long-term text search
        ltm = self.long_term.search(query, limit=10)
        results.extend([{"source": "long_term", **r} for r in ltm])

        # Semantic search
        if semantic and self.vector.is_available:
            vec = self.vector.search(query)
            results.extend([{"source": "vector", **r} for r in vec])

        return results

    def get_context_window(self, session_id: str, max_turns: int = 20) -> List[Dict]:
        """Get recent conversation for context injection."""
        return self.long_term.get_conversation(session_id, limit=max_turns)

    def add_turn(self, session_id: str, role: str, content: str) -> None:
        self.long_term.add_conversation(session_id, role, content)
        self.short_term.store(f"conv_{session_id}_{role}", content, source="conversation")

    def set_preference(self, key: str, value: Any) -> None:
        self.long_term.set_preference(key, value)

    def get_preference(self, key: str, default: Any = None) -> Any:
        return self.long_term.get_preference(key, default)


_manager: Optional[MemoryManager] = None


def get_memory() -> MemoryManager:
    global _manager
    if _manager is None:
        _manager = MemoryManager()
    return _manager
