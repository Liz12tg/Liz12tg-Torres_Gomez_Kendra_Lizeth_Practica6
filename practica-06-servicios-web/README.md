# Práctica 6: servicios web

Versión web (REST/HTTP) de la boletería. Cada taquilla es un servicio independiente;
el vendedor es un cliente HTTP que recorre las taquillas en orden.

Solo usa el JDK (`com.sun.net.httpserver.HttpServer` y `java.net.http.HttpClient`).

## Endpoints

| Método | Ruta | Parámetros | Respuesta |
|--------|------|------------|-----------|
| POST | `/conectar` | `nombre` | `{"estado":"ok"\|"nodo_lleno","idCliente":N,"reloj":R}` |
| POST | `/vender` | `idCliente`, `nombre`, `reloj` | `{"estado":"ok"\|"agotado","disponibles":N,"reloj":R}` |
| POST | `/salir` | `idCliente`, `nombre`, `reloj` | `{"estado":"ok","reloj":R}` |
| GET | `/estado` | (ninguno) | `{"idNodo","evento","disponibles","reloj"}` |

Errores: 405 (método incorrecto), 400 (parámetro faltante o inválido), 403 (vender sin cupo).

## Compilar

Windows:

```bat
compilar.bat
```

Linux/macOS:

```bash
mkdir -p out && javac -encoding UTF-8 -d out src/*.java
```

## Ejecutar

Una terminal por taquilla. Argumentos del servidor: `boletos maxClientes puerto idNodo`.

```bat
java -cp out Servidor 2 1 5000 N1
java -cp out Servidor 10 2 5001 N2
```

Vendedor: `nombre boletos host:puerto ...`

```bat
java -cp out Vendedor "Vendedor 1" 4 localhost:5000 localhost:5001
```

Para levantar todo de una vez en Windows: `demo.bat`.

Probar la API a mano (las URLs van entre comillas por el `&`):

```bat
curl "localhost:5000/estado"
curl -X POST "localhost:5000/conectar?nombre=Liz"
```

Si los acentos salen raros en la consola: `chcp 65001`.

## Limitaciones conocidas

- Si un vendedor se cae entre `/conectar` y `/salir`, su cupo queda ocupado hasta reiniciar la taquilla.
- No hay autenticación ni HTTPS.
- El inventario vive en memoria.
- Si se pierde la respuesta de `/vender`, el vendedor no puede saber si el boleto se vendió.
