@echo off
title Natural Language Analytics Warehouse - Stop Services
echo ========================================================
echo  DANG DUNG CAC DICH VU DEV (PORT 8080, 8000, 5173)...
echo ========================================================

powershell -Command "Get-NetTCPConnection -LocalPort 8080,8000,5173 -State Listen -ErrorAction SilentlyContinue | ForEach-Object { Stop-Process -Id $_.OwningProcess -Force -ErrorAction SilentlyContinue }"

echo.
echo Da tat Java Backend, Python AI Service va React Frontend!
echo (Luu y: Docker CSDL van chay ngam de luu giu du lieu; neu muon tat Docker thi chay: docker stop nla-chinook-postgres-1 nla-chinook-mysql-1 nla-chinook-sqlserver-1 nla-auth-keycloak-1)
echo.
pause

