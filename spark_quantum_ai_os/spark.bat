@echo off
:: SPARK QUANTUM AI OS — Windows Launcher
:: Right-click this file and select "Run as Administrator"

NET SESSION >nul 2>&1
IF %ERRORLEVEL% NEQ 0 (
    echo Requesting Administrator privileges...
    powershell -Command "Start-Process '%~f0' -Verb RunAs"
    exit /b
)

title SPARK QUANTUM AI OS v1.0.1
color 0B
cls

echo.
echo  ============================================================
echo   SPARK QUANTUM AI OS v1.0.1
echo   Running as Administrator
echo  ============================================================
echo.

cd /d "%~dp0"

:: Check Python
python --version >nul 2>&1
IF %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Python not found. Install Python 3.11+ and add to PATH.
    pause
    exit /b 1
)

:: Check virtual environment
IF NOT EXIST ".venv\Scripts\activate.bat" (
    echo [SETUP] Creating virtual environment...
    python -m venv .venv
)

:: Activate venv
call .venv\Scripts\activate.bat

:: Install dependencies if needed
IF NOT EXIST ".venv\Lib\site-packages\openai" (
    echo [SETUP] Installing dependencies (first run only)...
    pip install -r requirements.txt --quiet
    playwright install chromium
)

:: Copy .env if not exists
IF NOT EXIST ".env" (
    copy .env.example .env >nul
    echo [INFO] Created .env — add your API keys to .env before running.
)

:: Launch SPARK
echo [SPARK] Starting...
echo.
python main.py

pause
