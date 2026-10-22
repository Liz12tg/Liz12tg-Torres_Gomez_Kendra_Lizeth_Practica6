@echo off
chcp 65001 >nul
if not exist out mkdir out
javac -encoding UTF-8 -d out src\*.java
if errorlevel 1 (
    echo Error de compilacion.
    exit /b 1
)
echo Compilado en out\
