@echo off
echo Stopping running Spring Boot app...
for /f "tokens=5" %%a in ('netstat -aon ^| findstr :8080 ^| findstr LISTENING') do (
    echo Killing PID %%a
    taskkill /PID %%a /F
)
timeout /t 2 /nobreak > nul
echo Building and starting app...
cd /d "%~dp0"
call mvnw.cmd spring-boot:run -Dspring-boot.run.profiles=local
