@echo off
chcp 65001 >nul
echo.
echo ========================================
echo   Qiniu Algorithm Simulator Startup Script
echo ========================================
echo.

REM Check if Python is installed
python --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Python is not installed or not in PATH
    echo Please install Python 3.7 or higher
    pause
    exit /b 1
)

REM Check dependencies
echo Checking Python dependencies...
python -c "import pika, qiniu, requests" 2>&1 | findstr "ImportError" >nul
if not errorlevel 1 (
    echo [WARNING] Missing Python dependencies, installing...
    pip install -r requirements.txt
    if errorlevel 1 (
        echo [ERROR] Failed to install dependencies
        pause
        exit /b 1
    )
    echo Dependencies installed successfully
)

REM Check environment configuration file
if not exist ".env" (
    echo [WARNING] .env configuration file not found
    echo Creating sample configuration from .env.example...
    copy ".env.example" ".env" >nul
    echo.
    echo ========================================
    echo   IMPORTANT: Configure Qiniu AccessKey and SecretKey
    echo ========================================
    echo 1. Open .env file
    echo 2. Replace QINIU_ACCESS_KEY and QINIU_SECRET_KEY with your actual keys
    echo 3. Save the file
    echo.
    echo Press any key to continue...
    pause >nul
)

REM Start algorithm simulator
echo.
echo Starting Qiniu Algorithm Simulator...
echo Press Ctrl+C to stop
echo.

python qiniu_algorithm_simulator.py

if errorlevel 1 (
    echo.
    echo [ERROR] Algorithm simulator failed to start
    pause
    exit /b 1
)

echo.
echo Algorithm simulator stopped
pause
