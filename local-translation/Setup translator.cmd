@echo off
setlocal
set "PYTHONUTF8=1"
chcp 65001 >nul
cd /d "%~dp0.."
if exist ".indictrans-venv\Scripts\python.exe" goto install
py -3.12 -m venv .indictrans-venv
if errorlevel 1 goto failed
:install
".indictrans-venv\Scripts\python.exe" -m pip install "torch==2.8.0" --index-url https://download.pytorch.org/whl/cpu
if errorlevel 1 goto failed
".indictrans-venv\Scripts\python.exe" -m pip install -r local-translation\requirements.txt
if errorlevel 1 goto failed
".indictrans-venv\Scripts\python.exe" local-translation\download_model.py
if errorlevel 1 goto failed
echo.
echo Setup succeeded. Open Start translator.cmd to connect your phone.
pause
exit /b 0
:failed
echo.
echo Setup is not complete. Read the message above and retry.
pause
exit /b 1
