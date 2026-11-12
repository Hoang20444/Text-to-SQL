@echo off
title Natural Language Analytics Warehouse - Start Services
cd /d "D:\MyP\Text-to-SQL-1"

echo ========================================================
echo  KHOI DONG TOAN BO HE THONG TEXT-TO-SQL
echo ========================================================

echo [1/4] Kiem tra Docker CSDL va Keycloak OIDC...
python data\scripts\docker_chinook.py up
python infra\local\oidc.py up

echo [2/4] Kiem tra Python AI Service (Port 8000)...
netstat -ano | findstr :8000 | findstr LISTENING >nul
if %errorlevel% equ 0 (
    echo   -^> Python AI Service da dang chay tren cong 8000.
) else (
    echo   -^> Dang khoi dong Python AI Service...
    start "Python AI Service (Port 8000)" powershell -NoExit -Command "cd D:\MyP\Text-to-SQL-1\ai-service; .\.venv\Scripts\Activate.ps1; $env:PYTHON_AI_TOKEN='Nla_ynJKqNl7nFl_nX3M5zUc1fyYKOJyJ_w02jH29!'; $env:LLM_TIMEOUT_MS='55000'; uvicorn app:app --host 127.0.0.1 --port 8000"
)

echo [3/4] Kiem tra Java Backend (Port 8080)...
netstat -ano | findstr :8080 | findstr LISTENING >nul
if %errorlevel% equ 0 (
    echo   -^> Java Backend da dang chay tren cong 8080.
) else (
    echo   -^> Dang khoi dong Java Backend...
    start "Java Backend (Port 8080)" powershell -NoExit -Command "cd D:\MyP\Text-to-SQL-1\backend; mvn spring-boot:run"
)

echo [4/4] Kiem tra React Frontend (Port 5173)...
netstat -ano | findstr :5173 | findstr LISTENING >nul
if %errorlevel% equ 0 (
    echo   -^> React Frontend da dang chay tren cong 5173.
) else (
    echo   -^> Dang khoi dong React Frontend...
    start "React Frontend (Port 5173)" powershell -NoExit -Command "cd D:\MyP\Text-to-SQL-1\frontend; npm run dev"
)

echo.
echo ========================================================
echo  HE THONG SAN SANG!
echo  Trang web chay tai: http://127.0.0.1:5173
echo ========================================================
pause
