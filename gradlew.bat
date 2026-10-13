@echo off
rem Простой загрузчик Gradle: скачивает Gradle при первом запуске и запускает его.
setlocal
set GRADLE_VERSION=7.6.4
set CACHE=%~dp0.gradle-dist
set GRADLE_HOME=%CACHE%\gradle-%GRADLE_VERSION%

if not exist "%GRADLE_HOME%\bin\gradle.bat" (
  if not exist "%CACHE%" mkdir "%CACHE%"
  echo Downloading Gradle %GRADLE_VERSION%...
  powershell -NoProfile -ExecutionPolicy Bypass -Command "[Net.ServicePointManager]::SecurityProtocol=[Net.SecurityProtocolType]::Tls12; $ProgressPreference='SilentlyContinue'; Invoke-WebRequest -Uri 'https://services.gradle.org/distributions/gradle-%GRADLE_VERSION%-bin.zip' -OutFile '%CACHE%\gradle.zip'; Expand-Archive -Force '%CACHE%\gradle.zip' '%CACHE%'"
  if errorlevel 1 exit /b 1
  del "%CACHE%\gradle.zip"
)

call "%GRADLE_HOME%\bin\gradle.bat" %*
exit /b %ERRORLEVEL%
