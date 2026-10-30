@echo off
chcp 65001 > nul

cd /d "%~dp0"

if exist "ultrafit-secrets.bat" call "ultrafit-secrets.bat"

if not defined ULTRAFIT_LOGIN (
    set /p "ULTRAFIT_LOGIN=Логин Ultrafit: "
)

if not defined ULTRAFIT_PASSWORD (
    for /f "usebackq delims=" %%P in (`powershell -NoProfile -Command "$value = Read-Host 'Пароль Ultrafit' -AsSecureString; $ptr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($value); try { [Runtime.InteropServices.Marshal]::PtrToStringBSTR($ptr) } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($ptr) }"`) do set "ULTRAFIT_PASSWORD=%%P"
)

where java > nul 2>&1
if errorlevel 1 (
    echo Java не найдена в PATH.
    goto :finish
)

java -jar ultrafitBot.jar --config=ultrafit-config.json

:finish
pause
