# SPARK QUANTUM AI OS — PowerShell Setup Script
# Run: powershell -ExecutionPolicy Bypass -File install.ps1

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  SPARK QUANTUM AI OS — Installation" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""

# Check admin
$currentPrincipal = [Security.Principal.WindowsPrincipal][Security.Principal.WindowsIdentity]::GetCurrent()
$isAdmin = $currentPrincipal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $isAdmin) {
    Write-Host "[WARN] Not running as Administrator. Some steps may fail." -ForegroundColor Yellow
}

# Check Python 3.11+
$pythonVersion = python --version 2>&1
if ($LASTEXITCODE -ne 0) {
    Write-Host "[ERROR] Python not found. Install Python 3.11+ from https://python.org" -ForegroundColor Red
    exit 1
}
Write-Host "[OK] $pythonVersion" -ForegroundColor Green

# Create venv
if (-not (Test-Path ".venv")) {
    Write-Host "[SETUP] Creating virtual environment..." -ForegroundColor Yellow
    python -m venv .venv
}
Write-Host "[OK] Virtual environment ready" -ForegroundColor Green

# Activate venv
& .\.venv\Scripts\Activate.ps1

# Upgrade pip
python -m pip install --upgrade pip --quiet

# Install requirements
Write-Host "[SETUP] Installing Python dependencies..." -ForegroundColor Yellow
pip install -r requirements.txt

# Playwright browsers
Write-Host "[SETUP] Installing Playwright browsers..." -ForegroundColor Yellow
playwright install chromium

# Check Tesseract OCR
$tesseract = Get-Command tesseract -ErrorAction SilentlyContinue
if (-not $tesseract) {
    Write-Host "[WARN] Tesseract OCR not found." -ForegroundColor Yellow
    Write-Host "       Download: https://github.com/UB-Mannheim/tesseract/wiki" -ForegroundColor Yellow
    Write-Host "       After install, add to PATH: C:\Program Files\Tesseract-OCR" -ForegroundColor Yellow
} else {
    Write-Host "[OK] Tesseract OCR found" -ForegroundColor Green
}

# Check Ollama
$ollama = Get-Command ollama -ErrorAction SilentlyContinue
if (-not $ollama) {
    Write-Host "[INFO] Ollama not found (optional for local models)." -ForegroundColor Cyan
    Write-Host "       Download: https://ollama.ai" -ForegroundColor Cyan
} else {
    Write-Host "[OK] Ollama found" -ForegroundColor Green
}

# Create .env
if (-not (Test-Path ".env")) {
    Copy-Item ".env.example" ".env"
    Write-Host "[SETUP] Created .env — EDIT IT and add your API keys!" -ForegroundColor Yellow
} else {
    Write-Host "[OK] .env already exists" -ForegroundColor Green
}

# Create data directories
$dirs = @("data", "data\chroma", "logs")
foreach ($d in $dirs) {
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d | Out-Null }
}
Write-Host "[OK] Data directories created" -ForegroundColor Green

Write-Host ""
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  Installation Complete!" -ForegroundColor Green
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Next steps:" -ForegroundColor White
Write-Host "  1. Edit .env and add your API keys" -ForegroundColor White
Write-Host "  2. Right-click spark.bat → Run as Administrator" -ForegroundColor White
Write-Host ""
