@echo off
setlocal
title Natural Language Analytics Warehouse - Start Services
set "ROOT=%~dp0"
cd /d "%ROOT%"

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
    start "Python AI Service (Port 8000)" powershell -NoExit -ExecutionPolicy Bypass -File "%ROOT%ai-service\run.ps1"
)

echo [3/4] Kiem tra Java Backend (Port 8080)...
netstat -ano | findstr :8080 | findstr LISTENING >nul
if %errorlevel% equ 0 (
    echo   -^> Java Backend da dang chay tren cong 8080.
) else (
    echo   -^> Dang khoi dong Java Backend...
    start "Java Backend (Port 8080)" powershell -NoExit -Command "Set-Location '%ROOT%backend'; mvn spring-boot:run"
)

echo [4/4] Kiem tra React Frontend (Port 5173)...
netstat -ano | findstr :5173 | findstr LISTENING >nul
if %errorlevel% equ 0 (
    echo   -^> React Frontend da dang chay tren cong 5173.
) else (
    echo   -^> Dang khoi dong React Frontend...
    start "React Frontend (Port 5173)" powershell -NoExit -Command "Set-Location '%ROOT%frontend'; npm run dev"
)

echo.
echo ========================================================
echo  HE THONG SAN SANG!
echo  Trang web chay tai: http://127.0.0.1:5173
echo ========================================================
pause
