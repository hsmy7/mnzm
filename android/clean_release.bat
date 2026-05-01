@echo off
echo Attempting to delete release directory...
taskkill /f /im java.exe 2>nul
taskkill /f /im adb.exe 2>nul
timeout /t 2 /nobreak >nul
rmdir /s /q "C:\Mnzm\XianxiaSectNative\android\app\release" 2>nul
if exist "C:\Mnzm\XianxiaSectNative\android\app\release\app-release.apk" (
    echo File is still locked. Close Android Studio or any emulator and try again.
) else (
    echo Release directory cleaned successfully.
)
pause
