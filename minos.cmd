@echo off
setlocal
set "MINOS_JAR=%~dp0target\minos-code-intelligence-0.1.0-SNAPSHOT.jar"
if not exist "%MINOS_JAR%" (
  echo error: MINOS JAR is missing. Run .\mvnw.cmd clean package first. 1>&2
  exit /b 1
)
java -jar "%MINOS_JAR%" %*
exit /b %ERRORLEVEL%
