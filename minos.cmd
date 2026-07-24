@echo off
setlocal
set "MINOS_JAR="
for /f "delims=" %%F in ('dir /b /a-d /o-d "%~dp0target\minos-code-intelligence-*-all.jar" 2^>nul') do if not defined MINOS_JAR set "MINOS_JAR=%~dp0target\%%F"
if not defined MINOS_JAR (
  echo error: MINOS shaded JAR is missing. Run .\mvnw.cmd clean package first. 1>&2
  exit /b 1
)
java -jar "%MINOS_JAR%" %*
exit /b %ERRORLEVEL%
