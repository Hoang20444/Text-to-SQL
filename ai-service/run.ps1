Set-Location $PSScriptRoot
& .\.venv\Scripts\Activate.ps1
$env:PYTHON_AI_TOKEN = "Nla_ynJKqNl7nFl_nX3M5zUc1fyYKOJyJ_w02jH29!"
$env:LLM_TIMEOUT_MS = "55000"
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host " DANG KHOI DONG PYTHON AI SERVICE (PORT 8000)..." -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan
uvicorn app:app --host 127.0.0.1 --port 8000
