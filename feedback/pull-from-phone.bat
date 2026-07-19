@echo off
REM Pull in-app feedback reports from the phone into this folder.
setlocal
cd /d "%~dp0"
adb pull /sdcard/Download/CupcakeFeedback/ .
echo.
echo Pulled into: %cd%
pause
