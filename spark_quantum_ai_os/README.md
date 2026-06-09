# ⚡ SPARK QUANTUM AI OS

**Version:** 1.0.1 | **User:** Mr. Shree Ranjan (Vayala)

A production-grade AI Operating System — voice-controlled, multi-agent, autonomous.

---

## 🚀 Quick Start (Windows — Run as Administrator)

### Step 1: Install

```powershell
# Right-click PowerShell → Run as Administrator
cd C:\Users\shree\Downloads\spark_ai_assistant_v1.0.1
powershell -ExecutionPolicy Bypass -File install.ps1
```

### Step 2: Add API Keys

Edit `.env`:
```env
OPENAI_API_KEY=sk-...
ANTHROPIC_API_KEY=sk-ant-...
GEMINI_API_KEY=...
```

> **Note:** Without API keys, SPARK uses Ollama local models (free).

### Step 3: Launch

```
Right-click spark.bat → Run as Administrator
```

Or from an Administrator terminal:
```bash
python main.py          # Full UI (PySide6)
python main.py --cli    # CLI mode (no UI needed)
```

---

## 📁 Project Structure

```
spark_quantum_ai_os/
├── main.py                          ← Entry point
├── spark.bat                        ← Windows launcher (Run as Admin)
├── install.ps1                      ← First-time setup
├── requirements.txt
├── .env.example
├── config/
│   └── settings.yaml                ← All configuration
├── core/
│   ├── settings.py                  ← Config loader
│   ├── events/bus.py                ← Async event bus
│   ├── router/model_router.py       ← LLM routing
│   ├── registry/agent_registry.py  ← Agent manager
│   └── executor/orchestrator.py    ← Multi-agent orchestrator
├── agents/
│   ├── base_agent.py               ← Base class for all agents
│   ├── executive/                  ← Plans & delegates tasks
│   ├── desktop/                    ← Computer control
│   ├── vision/                     ← Screen reading & OCR
│   ├── browser/                    ← Web browsing (Playwright)
│   ├── document/                   ← DOCX, PDF, PPTX, Excel
│   ├── research/                   ← Academic research
│   └── coding/                     ← Code generation & execution
├── memory/
│   └── memory_manager.py           ← Short-term + Long-term + Vector
├── voice/
│   └── voice_engine.py             ← Whisper STT + TTS + Wake word
├── security/
│   └── security_manager.py         ← RBAC + Audit log + Approvals
├── ui/
│   └── main_window.py              ← PySide6 dark dashboard
├── tests/
│   └── unit/test_core.py
├── data/                           ← Auto-created
│   ├── documents/                  ← Generated DOCX/PDF/PPTX
│   ├── research/                   ← Research results
│   └── code/                       ← Generated code
└── logs/
    ├── spark.log
    └── audit.db
```

---

## 🗣️ Usage Examples

### Voice
Say **"Spark"** then your command:
- *"Spark, write a literature review on intrusion detection systems"*
- *"Spark, write Python code for a REST API with FastAPI"*
- *"Spark, take a screenshot and describe what's on the screen"*
- *"Spark, search papers on transformer models and create a report"*

### Text (CLI or Chat)
```
You: Research quantum computing and write a 20-page report as PDF
You: Write a Python script to scrape product prices and save to Excel
You: Open Chrome and search for the latest AI news
You: Create a PowerPoint presentation on machine learning for 15 slides
```

---

## 🤖 Agents

| Agent | Capability |
|---|---|
| Executive Agent | Plans tasks, delegates to other agents |
| Research Agent | Academic paper search (Semantic Scholar, arXiv, CrossRef) |
| Document Agent | DOCX, PDF, PPTX, Excel, Markdown generation |
| Coding Agent | Generate, fix, review, run code in 10+ languages |
| Desktop Agent | Keyboard, mouse, file management, system control |
| Vision Agent | Screenshot analysis, OCR, webcam, UI reading |
| Browser Agent | Web search, form fill, Gmail, GitHub, Drive |

---

## 🧠 Memory System

| Layer | Storage | Purpose |
|---|---|---|
| Short-Term | In-process deque | Recent context, fast access |
| Long-Term | SQLite | Facts, preferences, history |
| Vector | ChromaDB | Semantic search |

---

## 🔒 Security

- All **destructive actions** require explicit approval
- Full **audit log** of every action
- **RBAC**: Admin / User / Guest / Agent roles
- Approval required for: file delete, code execution, send message, shutdown

---

## 🔧 Configuration

Edit `config/settings.yaml` to change:
- Wake word (default: "spark")
- Preferred models per task type
- UI theme and window size
- Security approval list
- Voice and vision settings

---

## 📦 External Requirements

| Tool | Purpose | Download |
|---|---|---|
| Python 3.11+ | Runtime | https://python.org |
| Tesseract OCR | Screen text extraction | https://github.com/UB-Mannheim/tesseract/wiki |
| Ollama (optional) | Local LLMs | https://ollama.ai |
| Redis (optional) | Caching | https://redis.io |

---

## 🧪 Running Tests

```bash
.venv\Scripts\activate
pytest tests/ -v
```

---

## 📈 Phases

| Phase | Status | Description |
|---|---|---|
| 1 | ✅ | Core architecture, settings, event bus |
| 2 | ✅ | Voice system (Whisper + TTS + wake word) |
| 3 | ✅ | Memory system (short/long-term/vector) |
| 4 | ✅ | Desktop control agent |
| 5 | ✅ | Vision agent (OCR, screen reading, webcam) |
| 6 | ✅ | Browser agent (Playwright) |
| 7 | ✅ | Document agent (DOCX, PDF, PPTX, Excel) |
| 8 | ✅ | Research + Coding agents |
| 9 | ✅ | Multi-agent orchestrator + PySide6 UI |
| 10 | 🔄 | Docker deployment |

---

*SPARK QUANTUM AI OS — Built for Mr. Shree Ranjan (Vayala)*
