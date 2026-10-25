@echo off
setlocal
set "PYTHONUTF8=1"
chcp 65001 >nul
cd /d "%~dp0.."
if not exist ".indictrans-venv\Scripts\python.exe" (
  echo Run Setup translator.cmd first.
  pause
  exit /b 1
)
if exist "local-translation\.state\connection.txt" start "" notepad.exe "local-translation\.state\connection.txt"
".indictrans-venv\Scripts\python.exe" local-translation\server.py
pause
