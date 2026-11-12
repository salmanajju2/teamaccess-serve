@echo off
title TeamAccess Signaling Server
echo ========================================================
echo Starting TeamAccess Signaling Server on port 9090...
echo ========================================================
cd /d "%~dp0"
node server.js
pause
