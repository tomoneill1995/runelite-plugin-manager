@echo off
rem Start the RuneLite plugin manager on RuneLite's bundled Java 11, so it works
rem whichever Java is first on PATH. Falls back to javaw from PATH.
set "JAR=%~dp0..\manager\build\libs\rl-sideloader-manager.jar"
set "RLJAVA=%LOCALAPPDATA%\RuneLite\jre\bin\javaw.exe"

if not exist "%JAR%" (
    echo Manager not built. Run: gradlew.bat build
    pause
    exit /b 1
)

if exist "%RLJAVA%" (
    start "" "%RLJAVA%" -jar "%JAR%"
) else (
    start "" javaw -jar "%JAR%"
)
