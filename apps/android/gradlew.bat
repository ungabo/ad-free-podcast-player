@echo off
setlocal
set SCRIPT_DIR=%~dp0
if "%SCRIPT_DIR:~-1%"=="\" set SCRIPT_DIR=%SCRIPT_DIR:~0,-1%
set GRADLE_HOME=%SCRIPT_DIR%\.tools\gradle\gradle-8.10.2
set JAVA_HOME=C:\Program Files\Unity\Hub\Editor\6000.4.6f1\Editor\Data\PlaybackEngines\AndroidPlayer\OpenJDK
set PATH=%JAVA_HOME%\bin;%PATH%
if not exist "%GRADLE_HOME%\bin\gradle.bat" (
  echo Local Gradle distribution not found at "%GRADLE_HOME%".
  exit /b 1
)
call "%GRADLE_HOME%\bin\gradle.bat" -p "%SCRIPT_DIR%" %*
