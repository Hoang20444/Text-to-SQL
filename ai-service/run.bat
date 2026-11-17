@echo off
setlocal
cd /d "%~dp0"
set "ENV_FILE=%~dp0..\.local\docker.env"
if not exist "%ENV_FILE%" (
    echo [ERROR] Khong tim thay %ENV_FILE%. Hay chay: python data\scripts\docker_chinook.py init
    exit /b 1
)
for /f "usebackq tokens=1,* delims==" %%A in ("%ENV_FILE%") do (
    if "%%A"=="PYTHON_AI_TOKEN" set "PYTHON_AI_TOKEN=%%B"
)
if "%PYTHON_AI_TOKEN%"=="" (
    echo [ERROR] Khong tim thay PYTHON_AI_TOKEN trong .local\docker.env
    exit /b 1
)
call .venv\Scripts\activate.bat
set LLM_TIMEOUT_MS=55000
set LLM_MAX_OUTPUT_TOKENS=512
echo ========================================================
echo  DANG KHOI DONG PYTHON AI SERVICE (PORT 8000)...
echo ========================================================
uvicorn app:app --host 127.0.0.1 --port 8000
