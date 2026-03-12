@echo off
chcp 65001 >nul
REM Elite Long-term Memory System CLI Wrapper
REM 精英长期记忆系统命令行工具

set MEMORY_DIR=%~dp0.trae\memory
set PYTHON_SCRIPT=%MEMORY_DIR%\cli\memory_cli.py

if "%1"=="" (
    python "%PYTHON_SCRIPT%" --help
) else (
    python "%PYTHON_SCRIPT%" %*
)
