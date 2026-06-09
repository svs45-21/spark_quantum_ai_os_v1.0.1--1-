"""
SPARK QUANTUM AI OS — Vision Agent
Phase 5: Screen understanding, OCR, webcam, image analysis.
Reads UI elements, extracts text, detects errors on screen.
"""
from __future__ import annotations

import asyncio
import base64
import logging
from pathlib import Path
from typing import Any, Dict, List, Optional

from agents.base_agent import AgentCapability, BaseAgent, Task
from core.events.bus import EventBus, EventType
from core.router.model_router import ModelRouter, TaskType

logger = logging.getLogger("spark.vision")


class OCREngine:
    """Tesseract-based OCR for extracting text from images."""

    def __init__(self) -> None:
        self._available = False
        try:
            import pytesseract
            self._tess = pytesseract
            self._available = True
            logger.info("Tesseract OCR initialized")
        except ImportError:
            logger.warning("pytesseract not installed — OCR disabled")

    def extract_text(self, image_bytes: bytes) -> str:
        if not self._available:
            return ""
        try:
            from PIL import Image
            import io
            img = Image.open(io.BytesIO(image_bytes))
            return self._tess.image_to_string(img).strip()
        except Exception as e:
            logger.error(f"OCR failed: {e}")
            return ""

    def extract_data(self, image_bytes: bytes) -> Dict:
        """Extract structured data (words + bounding boxes)."""
        if not self._available:
            return {}
        try:
            from PIL import Image
            import io
            img = Image.open(io.BytesIO(image_bytes))
            return self._tess.image_to_data(img, output_type=self._tess.Output.DICT)
        except Exception as e:
            logger.error(f"OCR data extraction failed: {e}")
            return {}


class WebcamCapture:
    """OpenCV webcam capture for face detection and environment awareness."""

    def __init__(self, cam_index: int = 0) -> None:
        self._index = cam_index
        self._cap = None
        self._available = False

    def open(self) -> bool:
        try:
            import cv2
            self._cv2 = cv2
            self._cap = cv2.VideoCapture(self._index)
            self._available = self._cap.isOpened()
            if self._available:
                logger.info(f"Webcam {self._index} opened")
            return self._available
        except ImportError:
            logger.warning("OpenCV not installed — webcam disabled")
            return False

    def capture_frame(self) -> Optional[bytes]:
        if not self._available or not self._cap:
            return None
        ret, frame = self._cap.read()
        if not ret:
            return None
        _, buf = self._cv2.imencode(".jpg", frame)
        return buf.tobytes()

    def detect_faces(self, frame_bytes: bytes) -> List[Dict]:
        if not self._available:
            return []
        try:
            import numpy as np
            from PIL import Image
            import io
            img = Image.open(io.BytesIO(frame_bytes))
            import numpy as np
            frame = np.array(img)
            gray = self._cv2.cvtColor(frame, self._cv2.COLOR_RGB2GRAY)

            cascade_path = self._cv2.data.haarcascades + "haarcascade_frontalface_default.xml"
            face_cascade = self._cv2.CascadeClassifier(cascade_path)
            faces = face_cascade.detectMultiScale(gray, 1.1, 4)
            return [{"x": int(x), "y": int(y), "w": int(w), "h": int(h)} for x, y, w, h in faces]
        except Exception as e:
            logger.error(f"Face detection failed: {e}")
            return []

    def close(self) -> None:
        if self._cap:
            self._cap.release()


class VisionAgent(BaseAgent):
    name = "vision_agent"
    description = "Screen reading, OCR, image analysis, webcam, UI understanding."
    capabilities = [
        AgentCapability("screenshot_analyze", "Take screenshot and analyze with AI"),
        AgentCapability("ocr_screen", "Extract all text from screen"),
        AgentCapability("read_image", "Analyze an image file"),
        AgentCapability("webcam_capture", "Capture frame from webcam"),
        AgentCapability("detect_faces", "Detect faces in webcam feed"),
        AgentCapability("find_ui_element", "Find a UI element on screen"),
        AgentCapability("read_error", "Detect and read error dialogs on screen"),
    ]

    def __init__(
        self,
        router: Optional[ModelRouter] = None,
        bus: Optional[EventBus] = None,
        settings=None,
    ) -> None:
        super().__init__(bus)
        from core.settings import get_settings
        cfg = settings or get_settings()
        self._router = router or ModelRouter()
        self._ocr = OCREngine()
        self._webcam = WebcamCapture(cfg.vision.webcam_index)
        self._desktop_ctrl = None  # Injected after init to avoid circular

    def set_desktop_controller(self, ctrl) -> None:
        self._desktop_ctrl = ctrl

    async def initialize(self) -> None:
        self._webcam.open()

    async def cleanup(self) -> None:
        self._webcam.close()

    async def execute(self, task: Task) -> Any:
        action = task.payload.get("action", "")
        params = task.payload.get("params", {})

        if action == "screenshot_analyze":
            return await self._screenshot_analyze(params.get("question", "What is on this screen?"))

        elif action == "ocr_screen":
            return await self._ocr_screen()

        elif action == "read_image":
            path = params.get("path", "")
            question = params.get("question", "Describe this image in detail.")
            return await self._analyze_image_file(path, question)

        elif action == "webcam_capture":
            return await self._webcam_capture()

        elif action == "detect_faces":
            return await self._detect_faces()

        elif action == "find_ui_element":
            label = params.get("label", "")
            return await self._find_ui_element(label)

        elif action == "read_error":
            return await self._read_error_on_screen()

        else:
            raise ValueError(f"Unknown vision action: {action}")

    async def _take_screenshot(self) -> Optional[bytes]:
        """Take screenshot using mss or PIL."""
        loop = asyncio.get_event_loop()
        try:
            import mss
            import mss.tools

            def _snap():
                with mss.mss() as sct:
                    monitor = sct.monitors[0]
                    img = sct.grab(monitor)
                    return mss.tools.to_png(img.rgb, img.size)

            return await loop.run_in_executor(None, _snap)
        except Exception:
            try:
                from PIL import ImageGrab
                import io

                def _snap2():
                    img = ImageGrab.grab()
                    buf = io.BytesIO()
                    img.save(buf, format="PNG")
                    return buf.getvalue()

                return await loop.run_in_executor(None, _snap2)
            except Exception as e:
                logger.error(f"Screenshot failed: {e}")
                return None

    async def _screenshot_analyze(self, question: str) -> str:
        img_bytes = await self._take_screenshot()
        if not img_bytes:
            return "Could not capture screenshot."

        await self._bus.emit(
            EventType.VISION_SCREENSHOT,
            {"size": len(img_bytes)},
            source=self.name,
        )

        # Send to vision LLM
        b64 = base64.b64encode(img_bytes).decode()
        try:
            response = await self._router.complete(
                messages=[
                    {
                        "role": "user",
                        "content": [
                            {
                                "type": "image_url",
                                "image_url": {"url": f"data:image/png;base64,{b64}"},
                            },
                            {"type": "text", "text": question},
                        ],
                    }
                ],
                task_type=TaskType.VISION,
                max_tokens=2000,
            )
        except Exception as e:
            logger.error(f"Vision LLM failed: {e}")
            # Fall back to OCR
            loop = asyncio.get_event_loop()
            response = await loop.run_in_executor(None, self._ocr.extract_text, img_bytes)

        await self._bus.emit(EventType.VISION_ANALYZED, {"result": response[:200]}, source=self.name)
        return response

    async def _ocr_screen(self) -> str:
        img_bytes = await self._take_screenshot()
        if not img_bytes:
            return ""
        loop = asyncio.get_event_loop()
        text = await loop.run_in_executor(None, self._ocr.extract_text, img_bytes)
        return text

    async def _analyze_image_file(self, path: str, question: str) -> str:
        try:
            img_bytes = Path(path).read_bytes()
        except Exception as e:
            return f"Could not read image: {e}"

        ext = Path(path).suffix.lower().lstrip(".")
        if ext not in ("jpg", "jpeg", "png", "gif", "webp"):
            ext = "jpeg"

        b64 = base64.b64encode(img_bytes).decode()
        response = await self._router.complete(
            messages=[
                {
                    "role": "user",
                    "content": [
                        {
                            "type": "image_url",
                            "image_url": {"url": f"data:{ext};base64,{b64}"},
                        },
                        {"type": "text", "text": question},
                    ],
                }
            ],
            task_type=TaskType.VISION,
        )
        return response

    async def _webcam_capture(self) -> Optional[bytes]:
        loop = asyncio.get_event_loop()
        frame = await loop.run_in_executor(None, self._webcam.capture_frame)
        return frame

    async def _detect_faces(self) -> List[Dict]:
        frame = await self._webcam_capture()
        if not frame:
            return []
        loop = asyncio.get_event_loop()
        faces = await loop.run_in_executor(None, self._webcam.detect_faces, frame)
        if faces:
            await self._bus.emit(
                EventType.VISION_FACE_DETECTED,
                {"count": len(faces), "faces": faces},
                source=self.name,
            )
        return faces

    async def _find_ui_element(self, label: str) -> Dict:
        """Find a UI element by label using vision + OCR."""
        img_bytes = await self._take_screenshot()
        if not img_bytes:
            return {}

        # Try OCR first for text elements
        loop = asyncio.get_event_loop()
        data = await loop.run_in_executor(None, self._ocr.extract_data, img_bytes)

        if data and "text" in data:
            for i, text in enumerate(data["text"]):
                if label.lower() in str(text).lower() and int(data.get("conf", [0])[i]) > 50:
                    return {
                        "found": True,
                        "label": text,
                        "x": data["left"][i] + data["width"][i] // 2,
                        "y": data["top"][i] + data["height"][i] // 2,
                        "confidence": data["conf"][i],
                    }

        return {"found": False, "label": label}

    async def _read_error_on_screen(self) -> str:
        return await self._screenshot_analyze(
            "Look for any error messages, dialog boxes, or warning popups on screen. "
            "If found, extract the exact error message text. If none, say 'No errors detected'."
        )
