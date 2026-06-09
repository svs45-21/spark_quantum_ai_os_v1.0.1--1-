"""
SPARK QUANTUM AI OS — PySide6 Main UI
Dark themed dashboard with: agent monitor, voice console, task queue,
memory browser, model status, and chat interface.
"""
from __future__ import annotations

import asyncio
import logging
import sys
from datetime import datetime
from typing import Optional

logger = logging.getLogger("spark.ui")

try:
    from PySide6.QtCore import (
        Qt, QTimer, Signal, QObject, QThread, Slot
    )
    from PySide6.QtGui import (
        QColor, QFont, QPalette, QTextCursor, QIcon
    )
    from PySide6.QtWidgets import (
        QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
        QLabel, QLineEdit, QPushButton, QTextEdit, QListWidget,
        QListWidgetItem, QTabWidget, QFrame, QSplitter, QScrollArea,
        QProgressBar, QStatusBar, QSizePolicy, QGroupBox,
        QTableWidget, QTableWidgetItem, QHeaderView,
    )
    PYSIDE6_AVAILABLE = True
except ImportError:
    PYSIDE6_AVAILABLE = False
    logger.warning("PySide6 not installed. UI disabled.")


DARK_STYLESHEET = """
QMainWindow, QWidget {
    background-color: #0D1117;
    color: #E6EDF3;
    font-family: 'Segoe UI', 'Arial', sans-serif;
    font-size: 13px;
}
QTabWidget::pane {
    border: 1px solid #21262D;
    background-color: #161B22;
    border-radius: 6px;
}
QTabBar::tab {
    background-color: #21262D;
    color: #8B949E;
    padding: 8px 20px;
    border-top-left-radius: 6px;
    border-top-right-radius: 6px;
    margin-right: 2px;
}
QTabBar::tab:selected {
    background-color: #00D4FF;
    color: #0D1117;
    font-weight: bold;
}
QTabBar::tab:hover:!selected {
    background-color: #30363D;
    color: #E6EDF3;
}
QPushButton {
    background-color: #21262D;
    color: #E6EDF3;
    border: 1px solid #30363D;
    border-radius: 6px;
    padding: 8px 16px;
    font-weight: 500;
}
QPushButton:hover {
    background-color: #30363D;
    border-color: #00D4FF;
}
QPushButton:pressed {
    background-color: #00D4FF;
    color: #0D1117;
}
QPushButton#primary {
    background-color: #00D4FF;
    color: #0D1117;
    font-weight: bold;
    border: none;
}
QPushButton#primary:hover {
    background-color: #33DEFF;
}
QPushButton#danger {
    background-color: #DA3633;
    color: white;
    border: none;
}
QLineEdit, QTextEdit {
    background-color: #161B22;
    color: #E6EDF3;
    border: 1px solid #30363D;
    border-radius: 6px;
    padding: 8px;
    selection-background-color: #00D4FF;
    selection-color: #0D1117;
}
QLineEdit:focus, QTextEdit:focus {
    border-color: #00D4FF;
}
QListWidget {
    background-color: #161B22;
    border: 1px solid #21262D;
    border-radius: 6px;
    alternate-background-color: #1C2128;
}
QListWidget::item:selected {
    background-color: #003D54;
    color: #00D4FF;
}
QListWidget::item:hover {
    background-color: #21262D;
}
QTableWidget {
    background-color: #161B22;
    gridline-color: #21262D;
    border: 1px solid #21262D;
    border-radius: 6px;
}
QTableWidget::item:selected {
    background-color: #003D54;
}
QHeaderView::section {
    background-color: #21262D;
    color: #8B949E;
    padding: 6px;
    border: none;
    font-weight: bold;
    font-size: 11px;
    text-transform: uppercase;
}
QScrollBar:vertical {
    background: #161B22;
    width: 8px;
    border-radius: 4px;
}
QScrollBar::handle:vertical {
    background: #30363D;
    border-radius: 4px;
    min-height: 20px;
}
QScrollBar::handle:vertical:hover { background: #00D4FF; }
QScrollBar::add-line:vertical, QScrollBar::sub-line:vertical { height: 0; }
QGroupBox {
    color: #8B949E;
    border: 1px solid #21262D;
    border-radius: 6px;
    margin-top: 8px;
    padding-top: 8px;
    font-size: 11px;
    font-weight: bold;
    text-transform: uppercase;
    letter-spacing: 1px;
}
QGroupBox::title {
    subcontrol-origin: margin;
    left: 10px;
    padding: 0 4px;
}
QProgressBar {
    background-color: #21262D;
    border-radius: 3px;
    height: 6px;
    text-align: center;
    color: transparent;
}
QProgressBar::chunk {
    background-color: #00D4FF;
    border-radius: 3px;
}
QStatusBar {
    background-color: #161B22;
    border-top: 1px solid #21262D;
    color: #8B949E;
    font-size: 11px;
}
QSplitter::handle {
    background-color: #21262D;
    width: 2px;
}
QFrame#card {
    background-color: #161B22;
    border: 1px solid #21262D;
    border-radius: 8px;
}
"""


class SparkSignals(QObject):
    """Qt signals for thread-safe UI updates from async code."""
    log_message = Signal(str, str)        # (message, level)
    agent_status = Signal(str, str)       # (agent_name, status)
    task_update = Signal(str, str, str)   # (task_name, agent, status)
    speak_text = Signal(str)              # text to speak
    approval_needed = Signal(str, str)    # (task_name, description)


if PYSIDE6_AVAILABLE:

    class AgentCard(QFrame):
        """Compact agent status card for the dashboard."""

        STATUS_COLORS = {
            "idle": "#238636",
            "running": "#00D4FF",
            "waiting_approval": "#F0883E",
            "error": "#DA3633",
            "stopped": "#8B949E",
            "initializing": "#8B949E",
        }

        def __init__(self, agent_name: str, parent=None):
            super().__init__(parent)
            self.setObjectName("card")
            self.setFixedHeight(75)
            self._agent_name = agent_name

            layout = QVBoxLayout(self)
            layout.setContentsMargins(12, 8, 12, 8)
            layout.setSpacing(4)

            top = QHBoxLayout()
            self._name_label = QLabel(agent_name.replace("_agent", "").replace("_", " ").title())
            self._name_label.setFont(QFont("Segoe UI", 10, QFont.Weight.Bold))
            top.addWidget(self._name_label)
            top.addStretch()
            self._status_dot = QLabel("●")
            self._status_dot.setStyleSheet("color: #238636; font-size: 16px;")
            top.addWidget(self._status_dot)
            layout.addLayout(top)

            self._task_label = QLabel("Idle")
            self._task_label.setStyleSheet("color: #8B949E; font-size: 11px;")
            layout.addWidget(self._task_label)

        def update_status(self, status: str, task: str = ""):
            color = self.STATUS_COLORS.get(status, "#8B949E")
            self._status_dot.setStyleSheet(f"color: {color}; font-size: 16px;")
            self._task_label.setText(task if task else status.replace("_", " ").title())

    class ConsoleLine(QLabel):
        """Colored log line for the voice/log console."""
        COLORS = {
            "INFO": "#E6EDF3",
            "DEBUG": "#8B949E",
            "WARNING": "#F0883E",
            "ERROR": "#DA3633",
            "SPARK": "#00D4FF",
            "USER": "#3FB950",
        }

        def __init__(self, message: str, level: str = "INFO"):
            ts = datetime.now().strftime("%H:%M:%S")
            color = self.COLORS.get(level, "#E6EDF3")
            super().__init__(f'<span style="color:#8B949E">[{ts}]</span> <span style="color:{color}">{message}</span>')
            self.setTextFormat(Qt.TextFormat.RichText)
            self.setWordWrap(True)
            self.setContentsMargins(0, 1, 0, 1)

    class ChatWidget(QWidget):
        """Main chat interface for interacting with SPARK."""
        command_sent = Signal(str)

        def __init__(self, parent=None):
            super().__init__(parent)
            layout = QVBoxLayout(self)
            layout.setContentsMargins(0, 0, 0, 0)
            layout.setSpacing(8)

            # Chat display
            self._chat = QTextEdit()
            self._chat.setReadOnly(True)
            self._chat.setMinimumHeight(400)
            layout.addWidget(self._chat)

            # Input area
            input_row = QHBoxLayout()
            self._input = QLineEdit()
            self._input.setPlaceholderText('Type a command or say "Spark ..."')
            self._input.setMinimumHeight(42)
            self._input.returnPressed.connect(self._send)
            input_row.addWidget(self._input)

            send_btn = QPushButton("Send")
            send_btn.setObjectName("primary")
            send_btn.setMinimumHeight(42)
            send_btn.setFixedWidth(80)
            send_btn.clicked.connect(self._send)
            input_row.addWidget(send_btn)

            voice_btn = QPushButton("🎤")
            voice_btn.setToolTip("Push to Talk")
            voice_btn.setMinimumHeight(42)
            voice_btn.setFixedWidth(42)
            input_row.addWidget(voice_btn)

            layout.addLayout(input_row)

        def _send(self):
            text = self._input.text().strip()
            if text:
                self._input.clear()
                self.add_message("You", text, "#3FB950")
                self.command_sent.emit(text)

        def add_message(self, sender: str, text: str, color: str = "#E6EDF3"):
            ts = datetime.now().strftime("%H:%M")
            html = (
                f'<div style="margin: 6px 0;">'
                f'<span style="color:{color};font-weight:bold;">{sender}</span> '
                f'<span style="color:#8B949E;font-size:11px;">{ts}</span><br>'
                f'<span style="color:#E6EDF3;">{text}</span>'
                f'</div>'
            )
            self._chat.append(html)
            self._chat.moveCursor(QTextCursor.MoveOperation.End)

        def add_spark_response(self, text: str):
            self.add_message("SPARK", text, "#00D4FF")

    class ApprovalWidget(QWidget):
        """Approval dialog for sensitive actions."""
        approved = Signal()
        denied = Signal()

        def __init__(self, task_name: str, description: str, parent=None):
            super().__init__(parent)
            layout = QVBoxLayout(self)

            title = QLabel(f"⚠️  Approval Required: {task_name}")
            title.setStyleSheet("color: #F0883E; font-size: 14px; font-weight: bold;")
            layout.addWidget(title)

            desc = QLabel(description)
            desc.setWordWrap(True)
            desc.setStyleSheet("color: #8B949E; padding: 8px;")
            layout.addWidget(desc)

            btns = QHBoxLayout()
            approve_btn = QPushButton("✓  Approve")
            approve_btn.setObjectName("primary")
            approve_btn.clicked.connect(self.approved)

            deny_btn = QPushButton("✗  Deny")
            deny_btn.setObjectName("danger")
            deny_btn.clicked.connect(self.denied)

            btns.addWidget(approve_btn)
            btns.addWidget(deny_btn)
            layout.addLayout(btns)

    class MainWindow(QMainWindow):
        """Main SPARK OS window."""

        def __init__(self, ctx: dict, signals: SparkSignals):
            super().__init__()
            self._ctx = ctx
            self._signals = signals
            self._agent_cards: dict = {}

            self._setup_window()
            self._build_ui()
            self._connect_signals()
            self._start_refresh_timer()
            self._show_greeting()

        def _setup_window(self):
            cfg = self._ctx["settings"]
            self.setWindowTitle(f"SPARK QUANTUM AI OS  v{cfg.spark.version}")
            self.resize(cfg.ui.window_width, cfg.ui.window_height)
            self.setMinimumSize(1100, 700)
            self.setStyleSheet(DARK_STYLESHEET)

        def _build_ui(self):
            central = QWidget()
            self.setCentralWidget(central)
            root = QVBoxLayout(central)
            root.setContentsMargins(0, 0, 0, 0)
            root.setSpacing(0)

            # ── Header bar ────────────────────────────────────────────────────
            header = QFrame()
            header.setFixedHeight(56)
            header.setStyleSheet(
                "background-color: #161B22; border-bottom: 1px solid #21262D;"
            )
            hlay = QHBoxLayout(header)
            hlay.setContentsMargins(16, 0, 16, 0)

            logo = QLabel("⚡ SPARK QUANTUM AI OS")
            logo.setStyleSheet("color: #00D4FF; font-size: 16px; font-weight: bold; letter-spacing: 1px;")
            hlay.addWidget(logo)
            hlay.addStretch()

            cfg = self._ctx["settings"]
            user_lbl = QLabel(f"👤  {cfg.spark.user.name}  ·  {cfg.spark.user.nickname}")
            user_lbl.setStyleSheet("color: #8B949E; font-size: 12px;")
            hlay.addWidget(user_lbl)

            self._status_indicator = QLabel("● Online")
            self._status_indicator.setStyleSheet("color: #238636; font-size: 12px; margin-left: 16px;")
            hlay.addWidget(self._status_indicator)

            root.addWidget(header)

            # ── Main content ──────────────────────────────────────────────────
            splitter = QSplitter(Qt.Orientation.Horizontal)
            splitter.setHandleWidth(2)

            # Left sidebar — agents
            sidebar = self._build_sidebar()
            splitter.addWidget(sidebar)

            # Right — tabs
            tabs = self._build_tabs()
            splitter.addWidget(tabs)

            splitter.setSizes([260, 1140])
            root.addWidget(splitter)

            # Status bar
            self._statusbar = QStatusBar()
            self.setStatusBar(self._statusbar)
            self._statusbar.showMessage("SPARK ready.")

        def _build_sidebar(self) -> QWidget:
            sidebar = QFrame()
            sidebar.setObjectName("card")
            sidebar.setFixedWidth(260)
            sidebar.setStyleSheet("background-color: #0D1117; border-right: 1px solid #21262D;")

            lay = QVBoxLayout(sidebar)
            lay.setContentsMargins(8, 12, 8, 12)
            lay.setSpacing(6)

            title = QLabel("ACTIVE AGENTS")
            title.setStyleSheet("color: #8B949E; font-size: 10px; font-weight: bold; letter-spacing: 2px; padding: 4px 8px;")
            lay.addWidget(title)

            # Agent cards
            registry = self._ctx["registry"]
            for agent in registry.all():
                card = AgentCard(agent.name)
                self._agent_cards[agent.name] = card
                lay.addWidget(card)

            lay.addStretch()

            # Quick actions
            actions_label = QLabel("QUICK ACTIONS")
            actions_label.setStyleSheet("color: #8B949E; font-size: 10px; font-weight: bold; letter-spacing: 2px; padding: 4px 8px; margin-top: 8px;")
            lay.addWidget(actions_label)

            for label, cmd in [
                ("📄 Literature Review", "Write a literature review"),
                ("💻 Write Code", "Write Python code for"),
                ("🔍 Research Topic", "Research the topic"),
                ("📊 Create Report", "Create a report on"),
                ("📸 Screenshot", "Take a screenshot"),
            ]:
                btn = QPushButton(label)
                btn.setFixedHeight(34)
                btn.clicked.connect(lambda checked, c=cmd: self._quick_action(c))
                lay.addWidget(btn)

            return sidebar

        def _build_tabs(self) -> QTabWidget:
            tabs = QTabWidget()
            tabs.setTabPosition(QTabWidget.TabPosition.North)

            # Chat tab
            self._chat = ChatWidget()
            self._chat.command_sent.connect(self._on_command)
            tabs.addTab(self._chat, "💬  Assistant")

            # Console tab
            console_widget = QWidget()
            clay = QVBoxLayout(console_widget)
            self._console_scroll = QScrollArea()
            self._console_scroll.setWidgetResizable(True)
            self._console_content = QWidget()
            self._console_layout = QVBoxLayout(self._console_content)
            self._console_layout.setAlignment(Qt.AlignmentFlag.AlignTop)
            self._console_layout.setSpacing(2)
            self._console_scroll.setWidget(self._console_content)
            clay.addWidget(self._console_scroll)
            tabs.addTab(console_widget, "📟  Console")

            # Task Queue tab
            tasks_widget = QWidget()
            tlay = QVBoxLayout(tasks_widget)
            self._task_table = QTableWidget(0, 4)
            self._task_table.setHorizontalHeaderLabels(["Task", "Agent", "Status", "Time"])
            self._task_table.horizontalHeader().setSectionResizeMode(0, QHeaderView.ResizeMode.Stretch)
            self._task_table.setAlternatingRowColors(True)
            self._task_table.setSelectionBehavior(QTableWidget.SelectionBehavior.SelectRows)
            tlay.addWidget(self._task_table)
            tabs.addTab(tasks_widget, "📋  Task Queue")

            # Memory tab
            mem_widget = QWidget()
            mlay = QVBoxLayout(mem_widget)
            mem_search = QLineEdit()
            mem_search.setPlaceholderText("Search memory...")
            mem_search.setFixedHeight(38)
            mlay.addWidget(mem_search)
            self._mem_list = QListWidget()
            self._mem_list.setAlternatingRowColors(True)
            mlay.addWidget(self._mem_list)
            tabs.addTab(mem_widget, "🧠  Memory")

            # Settings tab
            settings_widget = self._build_settings_tab()
            tabs.addTab(settings_widget, "⚙️  Settings")

            return tabs

        def _build_settings_tab(self) -> QWidget:
            w = QWidget()
            lay = QVBoxLayout(w)
            lay.setContentsMargins(20, 20, 20, 20)

            cfg = self._ctx["settings"]

            info = QGroupBox("System Information")
            ilay = QVBoxLayout(info)
            for label, value in [
                ("Version", cfg.spark.version),
                ("User", cfg.spark.user.name),
                ("Nickname", cfg.spark.user.nickname),
                ("Wake Word", cfg.spark.wake_word),
                ("Default Model", cfg.models.default),
                ("UI Theme", cfg.ui.theme),
            ]:
                row = QHBoxLayout()
                row.addWidget(QLabel(f"{label}:"))
                val = QLabel(str(value))
                val.setStyleSheet("color: #00D4FF; font-weight: bold;")
                row.addWidget(val)
                row.addStretch()
                ilay.addLayout(row)
            lay.addWidget(info)

            providers = QGroupBox("Model Providers")
            play = QVBoxLayout(providers)
            router = self._ctx["router"]
            for provider, available in router.get_available_providers().items():
                row = QHBoxLayout()
                row.addWidget(QLabel(provider.title()))
                status = QLabel("● Connected" if available else "○ Not configured")
                status.setStyleSheet(f"color: {'#238636' if available else '#8B949E'}; font-weight: bold;")
                row.addWidget(status)
                row.addStretch()
                play.addLayout(row)
            lay.addWidget(providers)
            lay.addStretch()

            return w

        def _connect_signals(self):
            self._signals.log_message.connect(self._on_log)
            self._signals.agent_status.connect(self._on_agent_status)
            self._signals.speak_text.connect(self._on_speak)
            self._signals.approval_needed.connect(self._on_approval_needed)

        def _start_refresh_timer(self):
            self._timer = QTimer(self)
            self._timer.timeout.connect(self._refresh_agents)
            self._timer.start(2000)  # Every 2 seconds

        def _refresh_agents(self):
            registry = self._ctx["registry"]
            for agent in registry.all():
                status_dict = agent.get_status_dict()
                card = self._agent_cards.get(agent.name)
                if card:
                    card.update_status(
                        status_dict["status"],
                        status_dict.get("current_task", ""),
                    )

        def _show_greeting(self):
            executive = self._ctx["executive"]
            greeting = executive.get_greeting()
            self._chat.add_spark_response(greeting)
            self._log(greeting, "SPARK")

        def _quick_action(self, prefix: str):
            self._chat._input.setText(prefix + " ")
            self._chat._input.setFocus()

        @Slot(str)
        def _on_command(self, text: str):
            loop = asyncio.get_event_loop()
            if loop.is_running():
                asyncio.run_coroutine_threadsafe(
                    self._dispatch_command(text), loop
                )

        async def _dispatch_command(self, text: str):
            from core.events.bus import EventType
            bus = self._ctx["bus"]
            await bus.emit(EventType.USER_COMMAND, {"text": text}, source="ui")

        @Slot(str, str)
        def _on_log(self, message: str, level: str):
            line = ConsoleLine(message, level)
            self._console_layout.addWidget(line)
            # Auto-scroll
            self._console_scroll.verticalScrollBar().setValue(
                self._console_scroll.verticalScrollBar().maximum()
            )

        @Slot(str, str)
        def _on_agent_status(self, agent_name: str, status: str):
            card = self._agent_cards.get(agent_name)
            if card:
                card.update_status(status)

        @Slot(str)
        def _on_speak(self, text: str):
            self._chat.add_spark_response(text)
            self._log(f"SPARK: {text}", "SPARK")

        @Slot(str, str)
        def _on_approval_needed(self, task_name: str, description: str):
            from PySide6.QtWidgets import QMessageBox
            msg = QMessageBox(self)
            msg.setWindowTitle("⚠️  Approval Required")
            msg.setText(f"<b>{task_name}</b><br><br>{description}")
            msg.setStandardButtons(QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No)
            msg.setDefaultButton(QMessageBox.StandardButton.No)
            result = msg.exec()
            # Emit approval/denial
            bus = self._ctx["bus"]
            loop = asyncio.get_event_loop()
            from core.events.bus import EventType
            event_type = EventType.USER_APPROVAL if result == QMessageBox.StandardButton.Yes else EventType.USER_DENIAL
            asyncio.run_coroutine_threadsafe(bus.emit(event_type, {}), loop)

        def _log(self, message: str, level: str = "INFO"):
            self._signals.log_message.emit(message, level)

        def closeEvent(self, event):
            loop = asyncio.get_event_loop()
            if loop.is_running():
                asyncio.run_coroutine_threadsafe(self._ctx["registry"].stop_all(), loop)
            event.accept()


def launch_pyside6(ctx: dict) -> int:
    """Launch the PySide6 UI. Returns exit code."""
    if not PYSIDE6_AVAILABLE:
        logger.error("PySide6 not installed. Run: pip install PySide6")
        return 1

    app = QApplication.instance() or QApplication(sys.argv)
    app.setApplicationName("SPARK QUANTUM AI OS")
    app.setApplicationVersion(ctx["settings"].spark.version)

    signals = SparkSignals()

    # Connect event bus to Qt signals
    bus = ctx["bus"]

    async def _on_status(event):
        msg = event.payload.get("message", "")
        if msg:
            signals.speak_text.emit(msg)
        signals.log_message.emit(str(event.payload), "INFO")

    async def _on_approval(event):
        signals.approval_needed.emit(
            event.payload.get("task_name", "Unknown"),
            event.payload.get("description", ""),
        )

    from core.events.bus import EventType
    bus.subscribe(EventType.AGENT_STATUS_UPDATE, _on_status)
    bus.subscribe(EventType.AGENT_NEEDS_APPROVAL, _on_approval)

    window = MainWindow(ctx, signals)
    window.show()

    return app.exec()
