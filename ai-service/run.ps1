Set-Location $PSScriptRoot
$envFile = Join-Path (Split-Path $PSScriptRoot -Parent) ".local\docker.env"
if (-not (Test-Path $envFile)) {
    Write-Error "Khong tim thay $envFile. Hay chay: python data/scripts/docker_chinook.py init"
    exit 1
}
$tokenLine = Get-Content $envFile | Where-Object { $_ -match "^PYTHON_AI_TOKEN=" } | Select-Object -First 1
if (-not $tokenLine) {
    Write-Error "Khong tim thay PYTHON_AI_TOKEN trong .local/docker.env"
    exit 1
}
$env:PYTHON_AI_TOKEN = ($tokenLine -split "=", 2)[1].Trim()
$env:LLM_TIMEOUT_MS = "55000"
$env:LLM_MAX_OUTPUT_TOKENS = "512"
if (-not $env:KNOWLEDGE_ENABLED) { $env:KNOWLEDGE_ENABLED = "true" }
& .\.venv\Scripts\Activate.ps1
Write-Host "========================================================" -ForegroundColor Cyan
Write-Host " DANG KHOI DONG PYTHON AI SERVICE (PORT 8000)..." -ForegroundColor Cyan
Write-Host "========================================================" -ForegroundColor Cyan
uvicorn app:app --host 127.0.0.1 --port 8000
