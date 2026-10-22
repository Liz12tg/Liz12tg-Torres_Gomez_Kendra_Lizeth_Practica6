@echo off
chcp 65001 >nul
call compilar.bat || exit /b 1
start "Sistema central" cmd /k "chcp 65001 >nul & java -cp out Sistema 6 5000"
start "Caja C1" cmd /k "chcp 65001 >nul & java -cp out Caja 1 5001 C1 localhost:5000"
start "Caja C2" cmd /k "chcp 65001 >nul & java -cp out Caja 1 5002 C2 localhost:5000"
timeout /t 2 >nul
start "Cliente Ana" cmd /k "chcp 65001 >nul & java -cp out Cliente Ana 2 localhost:5001 localhost:5002"
start "Cliente Beto" cmd /k "chcp 65001 >nul & java -cp out Cliente Beto 2 localhost:5001 localhost:5002"
start "Cliente Carla" cmd /k "chcp 65001 >nul & java -cp out Cliente Carla 2 localhost:5001 localhost:5002"
echo.
echo Tres clientes compiten por 6 boletos en dos cajas de un cliente cada una. Cierre las ventanas al terminar.
