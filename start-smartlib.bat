@echo off
rem Starts SmartLib on this computer: backend (port 8080), website (port 5173) and, if installed,
rem the Mailpit test inbox (port 8025). Each part opens in its own window; close a window to stop it.
title SmartLib launcher
cd /d "%~dp0"

netstat -ano | findstr /r /c:":5173 .*LISTENING" >nul && (
  echo SmartLib already seems to be running. Opening http://localhost:5173 ...
  start "" http://localhost:5173
  exit /b 0
)

set "MAILPIT=%USERPROFILE%\.smartlib-tools\mailpit\mailpit.exe"
if exist "%MAILPIT%" (
  start "SmartLib - Mailpit (test inbox)" /min "%MAILPIT%" --smtp 127.0.0.1:1025 --listen 127.0.0.1:8025 --database "%USERPROFILE%\.smartlib-tools\mailpit\mailpit.db"
)
start "SmartLib - Backend" /D "%~dp0backend" cmd /k mvnw.cmd spring-boot:run
start "SmartLib - Website" /D "%~dp0frontend" cmd /k npm run dev -- --port 5173 --strictPort

echo Starting SmartLib. The backend needs about a minute on first start.
echo Waiting for the backend...
:wait
timeout /t 5 /nobreak >nul
powershell -NoProfile -Command "try { (Invoke-WebRequest -UseBasicParsing -TimeoutSec 3 http://localhost:8080/api/public/site).StatusCode } catch { exit 1 }" >nul 2>&1 || goto wait
echo SmartLib is ready: http://localhost:5173
start "" http://localhost:5173
timeout /t 5 >nul
