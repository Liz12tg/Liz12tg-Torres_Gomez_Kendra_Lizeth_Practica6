# Práctica 6: servicios web

Versión web (REST/HTTP) de la boletería, con la lógica de una taquilla real: **un solo sistema central** con los boletos
del evento, **varias cajas** que atienden cada una a varios clientes a la vez, y **clientes** que llegan a una caja.
Todas las cajas venden del mismo inventario y el sistema lleva la cuenta de cuántos boletos sobran.

```
Cliente ──┐                    ┌── Caja C1 (máx. 2 clientes) ──┐
Cliente ──┼── (fila si todas ──┤                               ├── Sistema central (inventario único)
Cliente ──┘    están llenas)   └── Caja C2 (máx. 2 clientes) ──┘
```

| Programa | Rol |
|----------|-----|
| `Sistema` | Guarda los boletos del evento y vende de forma atómica (nunca vende de más). `/vender`, `/estado` |
| `Caja` | Limita cuántos clientes atiende a la vez (semáforo), controla sesiones, lease y límite por cliente, y reenvía cada venta al sistema |
| `Cliente` | Se forma en una caja; si está llena prueba con otra; si todas están llenas espera en la fila y reintenta |
| `Interfaz` | Página en `localhost:8080` que muestra cuántos boletos sobran, las cajas, los clientes y la bitácora |
| `Web` | Utilidades compartidas (token, HTTP, JSON, pool acotado) |

Solo usa el JDK 17+ (`com.sun.net.httpserver.HttpServer` y `java.net.http.HttpClient`).

## Inicio rápido

Windows (compila y abre el sistema, dos cajas, la interfaz y el navegador):

```bat
iniciar.bat
```

Linux/macOS/Codespaces: `./iniciar.sh`. La interfaz queda en <http://localhost:8080>.
En la página, el botón **Llegan 8 clientes a la vez** manda 8 clientes de 3 boletos para 20 disponibles: se forma fila y al final se agotan.

## Robustez

| Mejora | Qué resuelve | Dónde |
|--------|--------------|-------|
| Token `Bearer` en cada llamada (cliente→caja y caja→sistema) | Solo componentes autorizados venden (401 si falta o es incorrecto) | `Web.autenticado` |
| Sesión aleatoria (UUID) en `X-Sesion` | Nadie usa el cupo de otro cliente adivinando su `idCliente` | `Caja.conectar` |
| Pool de hilos acotado (`-Dhilos`, `-Dcola`) | Una avalancha de peticiones no agota la memoria | `Web.poolAcotado` |
| Lease de cupos (`-Dlease`, 15 s) | Si un cliente se cae, su lugar en la caja se libera solo | `Caja` |
| Idempotencia con `idPeticion` + reintentos | Si se pierde una respuesta, el reintento no vende un segundo boleto (la deduplicación vive en el sistema, así funciona aunque cambie de caja) | `Sistema.vender`, `Cliente` |
| Límite por cliente (`-Dlimite`, 8) | Un cliente no acapara el evento | `Caja.vender` |
| Validación de entradas | Reloj fuera de rango y nombres con saltos de línea o muy largos | `Web.leerReloj`, `Web.limpiar` |
| Venta atómica | Sin sobreventa aunque varias cajas vendan a la vez | `Sistema.vender` (`synchronized`) |

## Interfaz web

El navegador no habla con el sistema ni con las cajas: habla con `Interfaz`, que lanza los clientes y consulta `/estado` con el token.
Por eso el token nunca llega al navegador. `Interfaz` además:

- escucha solo en `localhost`, y rechaza un `Host` distinto de `localhost`/`127.0.0.1` (DNS rebinding);
- exige `X-Requested-With` en las acciones que modifican (CSRF);
- valida nombre y boletos (1 a 8) y limita a 30 los clientes activos.

```bat
java -cp out Interfaz 8080 localhost:5000 localhost:5001 localhost:5002
```


| Método | Ruta | Para qué |
|--------|------|----------|
| GET | `/` | La página |
| GET | `/api/panel?desde=N` | Sistema, cajas, clientes y eventos nuevos desde el evento N |
| POST | `/api/clientes?nombre=&boletos=` | Manda un cliente; la caja con la que empieza rota para repartirlos |

## Endpoints

Todas las rutas exigen `Authorization: Bearer <token>`.

**Sistema** (puerto 5000)

| Método | Ruta | Parámetros | Respuesta |
|--------|------|------------|-----------|
| POST | `/vender` | `caja`, `cliente`, `reloj`, `idPeticion` | `{"estado":"ok"\|"agotado","disponibles":N,"reloj":R}` (+ `"repetida":true` si es reintento) |
| GET | `/estado` | | `{"evento","total","vendidos","disponibles","reloj"}` |

**Caja** (puertos 5001, 5002). `/vender` y `/salir` exigen además `X-Sesion`.

| Método | Ruta | Parámetros | Respuesta |
|--------|------|------------|-----------|
| POST | `/conectar` | `nombre` | `{"estado":"ok"\|"caja_llena","idCliente":N,"sesion":"uuid","reloj":R}` |
| POST | `/vender` | `reloj`, `idPeticion` | `{"estado":"ok"\|"agotado","disponibles":N,"reloj":R}` |
| POST | `/salir` | `reloj` | `{"estado":"ok","reloj":R}` |
| GET | `/estado` | | `{"idCaja","ventas","maxClientes","sesiones","reloj"}` |

Errores: 400 (parámetro inválido), 401 (token), 403 (sesión inválida o vencida), 405 (método), 409 (el cliente llegó a su límite),
429 (demasiadas peticiones distintas en una sesión), 502 (la caja no alcanza al sistema).

## Configuración

Se pasa con `-D` antes de `-cp`. Todos los programas deben usar el **mismo token**.

| Propiedad | Por defecto | Para qué | Aplica a |
|-----------|-------------|----------|----------|
| `-Dtoken=` | `boleteria-2026` | Token de autenticación | todos |
| `-Dlease=` | 15 | Segundos sin actividad antes de liberar un cupo | Caja |
| `-Dlimite=` | 8 | Boletos máximos por cliente | Caja, Interfaz |
| `-Dventa=` | 800 | Milisegundos que tarda una venta (simula el proceso) | Sistema |
| `-Dhilos=` / `-Dcola=` | 16 / 64 | Hilos y cola del pool | Sistema, Caja |

## Compilar

Windows: `compilar.bat`. Linux/macOS: `mkdir -p out && javac -encoding UTF-8 -d out src/*.java`

## Ejecutar a mano

Una terminal por programa. `Sistema boletos puerto`, `Caja maxClientes puerto idCaja sistema(host:puerto)`.

```bat
java -cp out Sistema 20 5000
java -cp out Caja 2 5001 C1 localhost:5000
java -cp out Caja 2 5002 C2 localhost:5000
```

Cliente: `nombre boletos caja1 caja2 ...`

```bat
java -cp out Cliente "Ana" 3 localhost:5001 localhost:5002
java -cp out Cliente "Beto" 3 localhost:5001 localhost:5002
```

`demo.bat` levanta un sistema de 6 boletos, dos cajas de un cliente cada una y tres clientes en ventanas separadas.

Probar la API a mano (en PowerShell usar `curl.exe`):

```bat
curl.exe -H "Authorization: Bearer boleteria-2026" "localhost:5000/estado"
curl.exe -X POST -H "Authorization: Bearer boleteria-2026" "localhost:5001/conectar?nombre=Liz"
curl.exe -X POST -H "Authorization: Bearer boleteria-2026" -H "X-Sesion: <sesion devuelta>" "localhost:5001/vender?reloj=1&idPeticion=folio-1"
```

Repetir `/vender` con el mismo `idPeticion` devuelve la misma respuesta con `"repetida":true` y no vende otro boleto.
Si los acentos salen raros en la consola de Windows: `chcp 65001`.

