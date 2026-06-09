"""
SPARK QUANTUM AI OS — Core Settings
Loads config from settings.yaml and .env with full type validation.
"""
from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path
from typing import Dict, List, Optional

import yaml
from dotenv import load_dotenv
from pydantic import BaseModel, Field

load_dotenv()

ROOT = Path(__file__).parent.parent


# ── Sub-models ─────────────────────────────────────────────────────────────────

class UserConfig(BaseModel):
    name: str = "Mr. Shree Ranjan"
    nickname: str = "Vayala"


class SparkConfig(BaseModel):
    name: str = "SPARK QUANTUM AI OS"
    version: str = "1.0.1"
    user: UserConfig = Field(default_factory=UserConfig)
    wake_word: str = "spark"
    log_level: str = "INFO"
    admin_mode: bool = True


class ModelRoutingConfig(BaseModel):
    simple: str = "ollama/llama3"
    coding: str = "ollama/codellama"
    research: str = "openai/gpt-4o"
    vision: str = "openai/gpt-4o"
    reasoning: str = "anthropic/claude-opus-4"


class ProviderConfig(BaseModel):
    api_key: Optional[str] = None
    base_url: Optional[str] = None


class ModelsConfig(BaseModel):
    default: str = "ollama"
    routing: ModelRoutingConfig = Field(default_factory=ModelRoutingConfig)
    providers: Dict[str, ProviderConfig] = Field(default_factory=dict)


class MemoryConfig(BaseModel):
    short_term_max_tokens: int = 8000
    short_term_ttl_seconds: int = 3600
    long_term_db_path: str = "data/long_term.db"
    vector_db_path: str = "data/chroma"
    vector_collection: str = "spark_memory"
    knowledge_graph_db_path: str = "data/knowledge.db"


class VoiceConfig(BaseModel):
    whisper_model: str = "base"
    wake_word_sensitivity: float = 0.5
    language: str = "en"
    tts_engine: str = "pyttsx3"
    sample_rate: int = 16000


class VisionConfig(BaseModel):
    webcam_index: int = 0
    screenshot_interval: int = 2
    ocr_engine: str = "tesseract"


class DesktopConfig(BaseModel):
    require_confirmation: bool = True
    screenshot_on_action: bool = True
    action_delay_ms: int = 300


class BrowserConfig(BaseModel):
    engine: str = "playwright"
    headless: bool = False
    timeout_ms: int = 30000


class SecurityConfig(BaseModel):
    require_approval: List[str] = Field(default_factory=lambda: [
        "file_delete", "send_message", "execute_code", "shutdown", "browser_form_submit"
    ])
    audit_log: str = "logs/audit.log"
    rbac_enabled: bool = True


class UIConfig(BaseModel):
    theme: str = "dark"
    font_size: int = 13
    window_width: int = 1400
    window_height: int = 900
    accent_color: str = "#00D4FF"


class Settings(BaseModel):
    spark: SparkConfig = Field(default_factory=SparkConfig)
    models: ModelsConfig = Field(default_factory=ModelsConfig)
    memory: MemoryConfig = Field(default_factory=MemoryConfig)
    voice: VoiceConfig = Field(default_factory=VoiceConfig)
    vision: VisionConfig = Field(default_factory=VisionConfig)
    desktop: DesktopConfig = Field(default_factory=DesktopConfig)
    browser: BrowserConfig = Field(default_factory=BrowserConfig)
    security: SecurityConfig = Field(default_factory=SecurityConfig)
    ui: UIConfig = Field(default_factory=UIConfig)

    @classmethod
    def from_yaml(cls, path: Path) -> "Settings":
        with open(path) as f:
            raw = yaml.safe_load(f)

        # Flatten nested yaml into pydantic-compatible dict
        cfg: dict = {}

        s = raw.get("spark", {})
        cfg["spark"] = SparkConfig(
            name=s.get("name", "SPARK QUANTUM AI OS"),
            version=s.get("version", "1.0.1"),
            user=UserConfig(**s.get("user", {})),
            wake_word=s.get("wake_word", "spark"),
            log_level=s.get("log_level", "INFO"),
            admin_mode=s.get("admin_mode", True),
        )

        m = raw.get("models", {})
        cfg["models"] = ModelsConfig(
            default=m.get("default", "ollama"),
            routing=ModelRoutingConfig(**m.get("routing", {})),
            providers={
                k: ProviderConfig(
                    api_key=os.environ.get(f"{k.upper()}_API_KEY", v.get("api_key")),
                    base_url=v.get("base_url"),
                )
                for k, v in m.get("providers", {}).items()
            },
        )

        mem = raw.get("memory", {})
        cfg["memory"] = MemoryConfig(
            short_term_max_tokens=mem.get("short_term", {}).get("max_tokens", 8000),
            short_term_ttl_seconds=mem.get("short_term", {}).get("ttl_seconds", 3600),
            long_term_db_path=mem.get("long_term", {}).get("db_path", "data/long_term.db"),
            vector_db_path=mem.get("vector", {}).get("db_path", "data/chroma"),
            vector_collection=mem.get("vector", {}).get("collection", "spark_memory"),
            knowledge_graph_db_path=mem.get("knowledge_graph", {}).get("db_path", "data/knowledge.db"),
        )

        cfg["voice"] = VoiceConfig(**raw.get("voice", {}))
        cfg["vision"] = VisionConfig(**raw.get("vision", {}))

        d = raw.get("desktop", {})
        cfg["desktop"] = DesktopConfig(**d)

        b = raw.get("browser", {})
        cfg["browser"] = BrowserConfig(**b)

        sec = raw.get("security", {})
        cfg["security"] = SecurityConfig(
            require_approval=sec.get("require_approval", []),
            audit_log=sec.get("audit_log", "logs/audit.log"),
            rbac_enabled=sec.get("rbac_enabled", True),
        )

        ui = raw.get("ui", {})
        cfg["ui"] = UIConfig(**ui)

        return cls(**cfg)


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    yaml_path = ROOT / "config" / "settings.yaml"
    if yaml_path.exists():
        return Settings.from_yaml(yaml_path)
    return Settings()
