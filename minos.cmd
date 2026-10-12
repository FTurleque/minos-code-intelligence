@echo off
setlocal
set "MINOS_JAR="
for /f "delims=" %%F in ('dir /b /a-d /o-d "%~dp0target\minos-code-intelligence-*-all.jar" 2^>nul') do if not defined MINOS_JAR set "MINOS_JAR=%~dp0target\%%F"
if not defined MINOS_JAR (
  echo error: MINOS shaded JAR is missing. Run .\mvnw.cmd clean package first. 1>&2
  exit /b 1
)
rem cmd.exe looks for a bare command in the current directory before the PATH. The IntelliJ plugin runs
rem this launcher from the opened project: a java.bat or java.exe planted there would run instead of Java.
rem JAVA_HOME is used when it holds a java.exe; otherwise the current directory is taken out of the search.
rem The variable is inherited by Java and the processes it starts, and does not outlive this script (setlocal).
if defined JAVA_HOME if exist "%JAVA_HOME%\bin\java.exe" goto :java_home
set "NoDefaultCurrentDirectoryInExePath=1"
java -jar "%MINOS_JAR%" %*
exit /b %ERRORLEVEL%

:java_home
"%JAVA_HOME%\bin\java.exe" -jar "%MINOS_JAR%" %*
exit /b %ERRORLEVEL%
