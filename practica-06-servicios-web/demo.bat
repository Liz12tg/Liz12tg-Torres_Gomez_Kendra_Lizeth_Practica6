@echo off
chcp 65001 >nul
call compilar.bat || exit /b 1
start "Taquilla N1" cmd /k "chcp 65001 >nul & java -cp out Servidor 2 1 5000 N1"
start "Taquilla N2" cmd /k "chcp 65001 >nul & java -cp out Servidor 10 2 5001 N2"
timeout /t 2 >nul
java -cp out Vendedor "Vendedor 1" 4 localhost:5000 localhost:5001
echo.
echo Cierra las ventanas de las taquillas cuando termines.
