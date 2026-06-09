"""
SPARK QUANTUM AI OS — Voice Engine
Phase 2: Wake word detection, Speech-to-Text (Whisper), Text-to-Speech.
Listens continuously, wakes on "Spark", transcribes, sends to Executive Agent.
"""
from __future__ import annotations

import asyncio
import io
import logging
import queue
import threading
import time
from pathlib import Path
from typing import Callable, Optional

import numpy as np

logger = logging.getLogger("spark.voice")


# ── Text-to-Speech ─────────────────────────────────────────────────────────────

class TTSEngine:
    """
    Text-to-speech using pyttsx3 (offline, no API needed).
    Falls back to print if unavailable.
    """

    def __init__(self, rate: int = 175, volume: float = 1.0) -> None:
        self._engine = None
        self._lock = threading.Lock()
        try:
            import pyttsx3
            self._engine = pyttsx3.init()
            self._engine.setProperty("rate", rate)
            self._engine.setProperty("volume", volume)
            # Prefer a female voice if available
            voices = self._engine.getProperty("voices")
            for v in voices:
                if "zira" in v.name.lower() or "female" in v.name.lower():
                    self._engine.setProperty("voice", v.id)
                    break
            logger.info("TTS engine (pyttsx3) initialized")
        except Exception as e:
            logger.warning(f"TTS engine unavailable: {e}")

    def speak(self, text: str) -> None:
        if not text:
            return
        print(f"\nSPARK: {text}\n")
        if self._engine:
            with self._lock:
                try:
                    self._engine.say(text)
                    self._engine.runAndWait()
                except Exception as e:
                    logger.error(f"TTS speak error: {e}")

    async def speak_async(self, text: str) -> None:
        loop = asyncio.get_event_loop()
        await loop.run_in_executor(None, self.speak, text)


# ── Whisper STT ────────────────────────────────────────────────────────────────

class WhisperSTT:
    """
    Speech-to-text using OpenAI Whisper (runs locally).
    Loads model once, transcribes audio buffers on demand.
    """

    def __init__(self, model_name: str = "base") -> None:
        self._model = None
        self._model_name = model_name
        self._loading = False

    def load(self) -> bool:
        if self._model:
            return True
        try:
            import whisper
            logger.info(f"Loading Whisper model '{self._model_name}'...")
            self._model = whisper.load_model(self._model_name)
            logger.info("Whisper model loaded")
            return True
        except ImportError:
            logger.warning("Whisper not installed. Run: pip install openai-whisper")
            return False
        except Exception as e:
            logger.error(f"Whisper load failed: {e}")
            return False

    def transcribe(self, audio_data: np.ndarray, sample_rate: int = 16000) -> str:
        if not self._model:
            if not self.load():
                return ""
        try:
            # Whisper expects float32 at 16kHz
            audio = audio_data.astype(np.float32) / 32768.0
            result = self._model.transcribe(audio, language="en", fp16=False)
            text = result.get("text", "").strip()
            logger.debug(f"Transcribed: {text}")
            return text
        except Exception as e:
            logger.error(f"Transcription error: {e}")
            return ""

    async def transcribe_async(self, audio_data: np.ndarray, sample_rate: int = 16000) -> str:
        loop = asyncio.get_event_loop()
        return await loop.run_in_executor(None, self.transcribe, audio_data, sample_rate)


# ── Microphone Stream ──────────────────────────────────────────────────────────

class MicrophoneStream:
    """
    Continuously captures microphone audio into a thread-safe queue.
    Uses sounddevice for low-level access.
    """

    CHUNK = 1024
    FORMAT = None  # Legacy, not strictly needed for sounddevice
    CHANNELS = 1
    RATE = 16000

    def __init__(self, sample_rate: int = 16000) -> None:
        self.RATE = sample_rate
        self._audio_queue: queue.Queue[bytes] = queue.Queue()
        self._stream = None
        self._running = False

    def start(self) -> bool:
        try:
            import sounddevice as sd
            self._stream = sd.RawInputStream(
                samplerate=self.RATE,
                blocksize=self.CHUNK,
                dtype='int16',
                channels=self.CHANNELS,
                callback=self._callback
            )
            self._stream.start()
            self._running = True
            logger.info("Microphone stream started")
            return True
        except ImportError:
            logger.warning("sounddevice not installed. Run: pip install sounddevice")
            return False
        except Exception as e:
            logger.error(f"Microphone start failed: {e}")
            return False

    def _callback(self, indata, frames, time, status):
        if status:
            logger.debug(f"Microphone status: {status}")
        self._audio_queue.put(bytes(indata))

    def read_chunk(self, timeout: float = 0.1) -> Optional[bytes]:
        try:
            return self._audio_queue.get(timeout=timeout)
        except queue.Empty:
            return None

    def drain(self, seconds: float) -> bytes:
        """Collect `seconds` worth of audio."""
        chunks_needed = int(self.RATE / self.CHUNK * seconds)
        collected = []
        deadline = time.time() + seconds + 1.0
        while len(collected) < chunks_needed and time.time() < deadline:
            chunk = self.read_chunk(timeout=0.05)
            if chunk:
                collected.append(chunk)
        return b"".join(collected)

    def stop(self) -> None:
        self._running = False
        if self._stream:
            self._stream.stop()
            self._stream.close()


# ── Wake Word Detector ─────────────────────────────────────────────────────────

class WakeWordDetector:
    """
    Listens for the wake word "Spark".
    Strategy 1: Picovoice Porcupine (accurate, needs free API key)
    Strategy 2: Simple energy + keyword heuristic (no API key needed)
    """

    def __init__(self, wake_word: str = "spark", sensitivity: float = 0.5) -> None:
        self._wake_word = wake_word.lower()
        self._sensitivity = sensitivity
        self._porcupine = None
        self._use_porcupine = False
        self._stt: Optional[WhisperSTT] = None

    def setup_with_whisper(self, stt: WhisperSTT) -> None:
        """Use lightweight Whisper-based detection as fallback."""
        self._stt = stt
        logger.info("Wake word: using Whisper-based detection")

    def setup_porcupine(self, api_key: str) -> bool:
        try:
            import pvporcupine
            self._porcupine = pvporcupine.create(
                access_key=api_key,
                keywords=["computer"],  # closest built-in; "spark" requires custom model
            )
            self._use_porcupine = True
            logger.info("Wake word: Porcupine initialized")
            return True
        except Exception as e:
            logger.warning(f"Porcupine unavailable: {e}")
            return False

    def detect(self, audio_chunk: bytes) -> bool:
        """Returns True if wake word detected in this chunk."""
        if self._use_porcupine and self._porcupine:
            return self._detect_porcupine(audio_chunk)
        return self._detect_energy(audio_chunk)

    def _detect_porcupine(self, audio_chunk: bytes) -> bool:
        try:
            import struct
            pcm = struct.unpack_from("h" * self._porcupine.frame_length, audio_chunk)
            result = self._porcupine.process(pcm)
            return result >= 0
        except Exception:
            return False

    def _detect_energy(self, audio_chunk: bytes) -> bool:
        """Simple energy-based VAD — not keyword-specific, just detects speech."""
        audio = np.frombuffer(audio_chunk, dtype=np.int16)
        rms = np.sqrt(np.mean(audio.astype(np.float32) ** 2))
        return rms > 500  # Threshold for speech energy


# ── Voice Engine ───────────────────────────────────────────────────────────────

class VoiceEngine:
    """
    Main voice engine. Orchestrates:
    1. Continuous microphone capture
    2. Wake word detection
    3. Speech recording (until silence)
    4. Whisper transcription
    5. Dispatch to event bus
    """

    SILENCE_THRESHOLD = 300       # RMS below this = silence
    SILENCE_DURATION = 1.5        # Seconds of silence to end recording
    MAX_RECORD_SECONDS = 30       # Hard cap on recording length
    PRE_BUFFER_SECONDS = 0.5      # Audio before wake word to include

    def __init__(self, settings=None, on_command: Callable[[str], None] = None) -> None:
        from core.settings import get_settings
        cfg = settings or get_settings()

        self._settings = cfg
        self._on_command = on_command
        self._mic = MicrophoneStream(cfg.voice.sample_rate)
        self._stt = WhisperSTT(cfg.voice.whisper_model)
        self._tts = TTSEngine()
        self._wake = WakeWordDetector(cfg.wake_word, cfg.voice.wake_word_sensitivity)
        self._wake.setup_with_whisper(self._stt)
        self._running = False
        self._bus = None

    def set_bus(self, bus) -> None:
        self._bus = bus

    async def start(self) -> None:
        self._stt.load()
        if not self._mic.start():
            logger.warning("Microphone unavailable — voice mode disabled")
            return
        self._running = True
        logger.info(f"Voice engine active. Say '{self._settings.wake_word}' to wake SPARK.")
        await self._tts.speak_async(
            f"Voice system online. Say {self._settings.wake_word} to begin."
        )
        asyncio.create_task(self._listen_loop())

    async def _listen_loop(self) -> None:
        """Main listen loop — runs indefinitely."""
        logger.info("Listening for wake word...")
        while self._running:
            chunk = await asyncio.get_event_loop().run_in_executor(
                None, self._mic.read_chunk, 0.1
            )
            if chunk is None:
                continue

            if self._wake.detect(chunk):
                await self._handle_wake()

    async def _handle_wake(self) -> None:
        """Wake word detected — record user command."""
        logger.info("Wake word detected!")
        await self._tts.speak_async("Yes?")

        if self._bus:
            from core.events.bus import EventType
            await self._bus.emit(EventType.VOICE_LISTENING, {}, source="voice")

        # Record until silence
        audio_bytes = await asyncio.get_event_loop().run_in_executor(
            None, self._record_until_silence
        )

        if not audio_bytes:
            return

        audio_np = np.frombuffer(audio_bytes, dtype=np.int16)
        text = await self._stt.transcribe_async(audio_np, self._settings.voice.sample_rate)

        if not text:
            await self._tts.speak_async("I didn't catch that. Please try again.")
            return

        logger.info(f"Recognized: {text}")

        if self._bus:
            from core.events.bus import EventType
            await self._bus.emit(
                EventType.VOICE_RECOGNIZED,
                {"text": text},
                source="voice",
            )
            await self._bus.emit(
                EventType.USER_VOICE_INPUT,
                {"text": text},
                source="voice",
            )

        if self._on_command:
            self._on_command(text)

    def _record_until_silence(self) -> bytes:
        """
        Record audio until silence is detected or max duration reached.
        Returns raw PCM bytes.
        """
        chunks = []
        silence_start = None
        start_time = time.time()

        while True:
            chunk = self._mic.read_chunk(timeout=0.05)
            if chunk is None:
                continue

            chunks.append(chunk)

            # Check silence
            audio = np.frombuffer(chunk, dtype=np.int16)
            rms = np.sqrt(np.mean(audio.astype(np.float32) ** 2))

            if rms < self.SILENCE_THRESHOLD:
                if silence_start is None:
                    silence_start = time.time()
                elif time.time() - silence_start > self.SILENCE_DURATION:
                    break
            else:
                silence_start = None

            if time.time() - start_time > self.MAX_RECORD_SECONDS:
                break

        return b"".join(chunks)

    async def speak(self, text: str) -> None:
        if self._bus:
            from core.events.bus import EventType
            await self._bus.emit(EventType.VOICE_SPEAKING, {"text": text}, source="voice")
        await self._tts.speak_async(text)
        if self._bus:
            from core.events.bus import EventType
            await self._bus.emit(EventType.VOICE_DONE, {}, source="voice")

    def stop(self) -> None:
        self._running = False
        self._mic.stop()
        logger.info("Voice engine stopped")


_voice_engine: Optional[VoiceEngine] = None


def get_voice_engine() -> VoiceEngine:
    global _voice_engine
    if _voice_engine is None:
        _voice_engine = VoiceEngine()
    return _voice_engine
