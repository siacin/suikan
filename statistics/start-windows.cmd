@echo off
setlocal
chcp 65001 >nul
py -3 --version >nul 2>&1
if not errorlevel 1 (
    py -3 "%~dp0windows_manager.py" %*
) else (
    python --version >nul 2>&1
    if errorlevel 1 (
        echo 请先安装 Python 3.10 或以上版本，并勾选添加到 PATH。
    ) else (
        python "%~dp0windows_manager.py" %*
    )
)
pause
