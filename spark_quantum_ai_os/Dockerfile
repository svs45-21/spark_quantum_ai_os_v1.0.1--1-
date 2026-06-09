# SPARK QUANTUM AI OS — Dockerfile
# Builds a headless API + CLI container (no PySide6/voice on server)
# For full desktop use, run natively on Windows via spark.bat

FROM python:3.11-slim

LABEL maintainer="Mr. Shree Ranjan (Vayala)"
LABEL description="SPARK QUANTUM AI OS v1.0.1"

# System deps
RUN apt-get update && apt-get install -y --no-install-recommends \
    tesseract-ocr \
    tesseract-ocr-eng \
    libglib2.0-0 \
    libsm6 \
    libxext6 \
    libxrender-dev \
    curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /spark

# Python deps (headless subset — no PySide6, no pyaudio)
COPY requirements.docker.txt .
RUN pip install --no-cache-dir --default-timeout=1000 -r requirements.docker.txt || pip install --no-cache-dir --default-timeout=1000 -r requirements.docker.txt

# Install Playwright chromium
RUN playwright install chromium --with-deps 2>/dev/null || true

# Copy project
COPY . .

# Create runtime directories
RUN mkdir -p data/chroma data/documents data/research data/code logs

# Environment
ENV PYTHONUNBUFFERED=1
ENV PYTHONPATH=/spark
ENV SPARK_LOG_LEVEL=INFO

EXPOSE 8765

# Default: start the API server
CMD ["uvicorn", "api.server:app", "--host", "0.0.0.0", "--port", "8765", "--log-level", "info"]
