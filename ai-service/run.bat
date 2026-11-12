@echo off
cd /d "%~dp0"
call .venv\Scripts\activate.bat
set PYTHON_AI_TOKEN=Nla_ynJKqNl7nFl_nX3M5zUc1fyYKOJyJ_w02jH29!
set LLM_TIMEOUT_MS=55000
set LLM_MAX_OUTPUT_TOKENS=250
echo ========================================================
echo  DANG KHOI DONG PYTHON AI SERVICE (PORT 8000)...
echo  (Da toi uu: LLM_MAX_OUTPUT_TOKENS=250)
echo ========================================================
uvicorn app:app --host 127.0.0.1 --port 8000
