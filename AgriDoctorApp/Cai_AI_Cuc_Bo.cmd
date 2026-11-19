@echo off
setlocal
cd /d "%~dp0backend"
if exist ".venv\Scripts\python.exe" (
  ".venv\Scripts\python.exe" -X utf8 setup_local_ai.py
) else (
  python -X utf8 setup_local_ai.py
)
if errorlevel 1 (
  pause
  exit /b 1
)
call "..\Mo_Tro_Ly.cmd"
