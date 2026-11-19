@echo off
setlocal
cd /d "%~dp0backend"
if exist ".venv\Scripts\python.exe" (
  ".venv\Scripts\python.exe" -X utf8 usb_demo.py
) else (
  python -X utf8 usb_demo.py
)
if errorlevel 1 pause
