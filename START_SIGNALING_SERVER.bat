@echo off
title TeamAccess Signaling Server
echo =======================================================
echo  TeamAccess Remote - WebRTC Signaling Server
echo =======================================================
cd /d "%~dp0server"
node server.js
pause
