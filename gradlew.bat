@echo off
setlocal
set VERSION=9.5.0
set BASE=%USERPROFILE%\.gradle\second-brain-bootstrap
set ZIP=%BASE%\gradle-%VERSION%-bin.zip
set HOME_DIR=%BASE%\gradle-%VERSION%
if exist "%HOME_DIR%\bin\gradle.bat" goto run
if not exist "%BASE%" mkdir "%BASE%"
if not exist "%ZIP%" powershell -NoProfile -Command "Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-9.5.0-bin.zip' -OutFile '%ZIP%'"
powershell -NoProfile -Command "$h=(Get-FileHash -Algorithm SHA256 '%ZIP%').Hash.ToLower(); if($h -ne '553c78f50dafcd54d65b9a444649057857469edf836431389695608536d6b746'){exit 1}"
if errorlevel 1 exit /b 1
powershell -NoProfile -Command "Expand-Archive -Path '%ZIP%' -DestinationPath '%BASE%' -Force"
:run
call "%HOME_DIR%\bin\gradle.bat" %*
