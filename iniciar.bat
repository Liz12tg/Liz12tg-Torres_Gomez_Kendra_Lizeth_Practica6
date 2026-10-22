@echo off
chcp 65001 >nul
call compilar.bat || exit /b 1
start "Sistema central" cmd /k "chcp 65001 >nul & java -cp out Sistema 20 5000"
start "Caja C1" cmd /k "chcp 65001 >nul & java -cp out Caja 2 5001 C1 localhost:5000"
start "Caja C2" cmd /k "chcp 65001 >nul & java -cp out Caja 2 5002 C2 localhost:5000"
timeout /t 2 >nul
start "Interfaz" cmd /k "chcp 65001 >nul & java -cp out Interfaz 8080 localhost:5000 localhost:5001 localhost:5002"
timeout /t 3 >nul
start http://localhost:8080
echo.
echo Sistema, dos cajas e interfaz iniciados. Cierre las cuatro ventanas para detenerlos.
