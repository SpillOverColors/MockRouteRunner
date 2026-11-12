@echo off
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0patch_tool.ps1" %*
