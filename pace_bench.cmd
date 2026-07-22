@echo off
setlocal
set ROOT=%~dp0
if not exist "%ROOT%target\pace-bench.jar" python "%ROOT%scripts\run_maven.py" -q -f "%ROOT%pom.xml" -DskipTests package || exit /b 1
java -jar "%ROOT%target\pace-bench.jar" %*
