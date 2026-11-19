@echo off
setlocal
cd /d "%~dp0backend"
if exist ".venv\Scripts\python.exe" (
  ".venv\Scripts\python.exe" -X utf8 local_launcher.py
) else (
  python -X utf8 local_launcher.py
)
if errorlevel 1 pause
