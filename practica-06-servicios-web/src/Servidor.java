import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class Servidor {
    private static final String TOKEN_DEFECTO = "boleteria-2026";
    private static final String TOKEN = System.getProperty("token", TOKEN_DEFECTO);
    private static final long LEASE_MS = Long.getLong("lease", 15L) * 1000;
    private static final int HILOS = Integer.getInteger("hilos", 16);
    private static final int COLA = Integer.getInteger("cola", 64);
    private static final int RELOJ_MAX = 1_000_000_000;
    private static final int NOMBRE_MAX = 40;
    private static final int RESPUESTAS_MAX = 100;

    private static class Sesion {
        final int idCliente;
        final String nombre;
        volatile long ultimaActividad = System.currentTimeMillis();
        final Map<String, String> respuestas = new ConcurrentHashMap<>();

        Sesion(int idCliente, String nombre) {
            this.idCliente = idCliente;
            this.nombre = nombre;
        }

        void tocar() { ultimaActividad = System.currentTimeMillis(); }
    }

    private final String idNodo;
    private final String evento;
    private int disponibles;
    private int reloj;
    private final Semaphore cupos;
    private final AtomicInteger contadorClientes = new AtomicInteger(0);
    private final Map<String, Sesion> sesiones = new ConcurrentHashMap<>();

    public Servidor(String idNodo, String evento, int disponibles, int maxClientes) {
        this.idNodo = idNodo;
        this.evento = evento;
        this.disponibles = disponibles;
        this.cupos = new Semaphore(maxClientes);
        this.reloj = 0;
    }

    public synchronized int eventoLocal() {
        reloj++;
        return reloj;
    }

    public synchronized int actualizarConMensaje(int relojRecibido) {
        reloj = Math.max(reloj, relojRecibido) + 1;
        return reloj;
    }

    public synchronized int getReloj() { return reloj; }

    public synchronized boolean vender(String nombre, int idCliente, int relojVendedor) {
        reloj = Math.max(reloj, relojVendedor) + 1;

        if (disponibles <= 0) {
            System.out.println("[" + idNodo + "][reloj " + reloj + "][cliente #" + idCliente + " " + nombre + "] No hay boletos disponibles para " + evento);
            return false;
        }

        System.out.println("[" + idNodo + "][reloj " + reloj + "][cliente #" + idCliente + " " + nombre + "] Procesando venta... Boletos antes: " + disponibles);
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        disponibles--;
        reloj++;
        System.out.println("[" + idNodo + "][reloj " + reloj + "][cliente #" + idCliente + " " + nombre + "] Boleto vendido. Quedan: " + disponibles);
        return true;
    }

    public synchronized int getDisponibles() { return disponibles; }

    public boolean intentarOcuparCupo() { return cupos.tryAcquire(); }
    public void liberarCupo() { cupos.release(); }
    public int siguienteIdCliente() { return contadorClientes.incrementAndGet(); }

    private static Map<String, String> parametros(HttpExchange ex) {
        Map<String, String> m = new HashMap<>();
        String q = ex.getRequestURI().getRawQuery();
        if (q != null) {
            for (String par : q.split("&")) {
                String[] kv = par.split("=", 2);
                m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8),
                      kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
            }
        }
        return m;
    }

    private static void responder(HttpExchange ex, int codigo, String json) throws IOException {
        byte[] cuerpo = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        ex.sendResponseHeaders(codigo, cuerpo.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(cuerpo); }
    }

    private static boolean autenticado(HttpExchange ex) {
        String recibido = ex.getRequestHeaders().getFirst("Authorization");
        if (recibido == null) { return false; }
        String esperado = "Bearer " + TOKEN;
        return MessageDigest.isEqual(recibido.getBytes(StandardCharsets.UTF_8), esperado.getBytes(StandardCharsets.UTF_8));
    }

    private static String limpiar(String texto) {
        if (texto == null) { return ""; }
        String limpio = texto.replaceAll("\\p{Cntrl}", "").trim();
        return limpio.length() > NOMBRE_MAX ? limpio.substring(0, NOMBRE_MAX) : limpio;
    }

    private static int leerReloj(String texto) {
        int valor = Integer.parseInt(texto);
        if (valor < 0 || valor > RELOJ_MAX) {
            throw new NumberFormatException("reloj fuera de rango");
        }
        return valor;
    }

    private interface Accion { void ejecutar(HttpExchange ex, Map<String, String> p) throws IOException; }

    private static void ruta(HttpServer http, String camino, String metodo, Accion accion) {
        http.createContext(camino, ex -> {
            try {
                if (!autenticado(ex)) {
                    ex.getResponseHeaders().set("WWW-Authenticate", "Bearer");
                    responder(ex, 401, "{\"error\":\"Token ausente o invalido\"}");
                    return;
                }
                if (!ex.getRequestMethod().equalsIgnoreCase(metodo)) {
                    responder(ex, 405, "{\"error\":\"Use el metodo " + metodo + "\"}");
                    return;
                }
                accion.ejecutar(ex, parametros(ex));
            } catch (NumberFormatException e) {
                responder(ex, 400, "{\"error\":\"Parametro numerico invalido o fuera de rango\"}");
            } finally {
                ex.close();
            }
        });
    }

    private void conectar(HttpExchange ex, Map<String, String> p) throws IOException {
        String nombre = limpiar(p.get("nombre"));
        if (nombre.isEmpty()) {
            responder(ex, 400, "{\"error\":\"Falta el parametro nombre\"}");
            return;
        }
        int idCliente = siguienteIdCliente();
        int r = eventoLocal();
        System.out.println("[" + idNodo + "][reloj " + r + "] Conexión entrante -> cliente #" + idCliente);

        if (!intentarOcuparCupo()) {
            r = eventoLocal();
            System.out.println("[" + idNodo + "][reloj " + r + "][cliente #" + idCliente + "] La taquilla está ocupada.");
            responder(ex, 200, "{\"estado\":\"nodo_lleno\",\"idCliente\":" + idCliente + ",\"reloj\":" + r + "}");
            return;
        }

        String idSesion = UUID.randomUUID().toString();
        sesiones.put(idSesion, new Sesion(idCliente, nombre));
        System.out.println("[" + idNodo + "][cliente #" + idCliente + "] Atendiendo a " + nombre);
        responder(ex, 200, "{\"estado\":\"ok\",\"idCliente\":" + idCliente + ",\"sesion\":\"" + idSesion + "\",\"reloj\":" + r + "}");
    }

    private void vender(HttpExchange ex, Map<String, String> p) throws IOException {
        String idSesion = ex.getRequestHeaders().getFirst("X-Sesion");
        Sesion s = idSesion == null ? null : sesiones.get(idSesion);
        if (s == null) {
            responder(ex, 403, "{\"error\":\"Sesion invalida o vencida. Use /conectar\"}");
            return;
        }

        String idPeticion = p.get("idPeticion");
        if (idPeticion == null || idPeticion.isBlank() || idPeticion.length() > 64) {
            responder(ex, 400, "{\"error\":\"Falta idPeticion (maximo 64 caracteres)\"}");
            return;
        }
        int relojV = leerReloj(p.get("reloj"));

        synchronized (s) {
            if (!sesiones.containsKey(idSesion)) {
                responder(ex, 403, "{\"error\":\"Sesion invalida o vencida. Use /conectar\"}");
                return;
            }
            s.tocar();

            String previa = s.respuestas.get(idPeticion);
            if (previa != null) {
                int r = actualizarConMensaje(relojV);
                System.out.println("[" + idNodo + "][reloj " + r + "][cliente #" + s.idCliente + " " + s.nombre + "] Petición repetida " + idPeticion.substring(0, Math.min(8, idPeticion.length())) + ": se devuelve la respuesta guardada, sin vender otro boleto.");
                responder(ex, 200, previa.substring(0, previa.length() - 1) + ",\"repetida\":true}");
                return;
            }
            if (s.respuestas.size() >= RESPUESTAS_MAX) {
                responder(ex, 429, "{\"error\":\"Demasiadas peticiones en esta sesion\"}");
                return;
            }

            boolean vendido = vender(s.nombre, s.idCliente, relojV);
            String json = "{\"estado\":\"" + (vendido ? "ok" : "agotado") + "\",\"disponibles\":" + getDisponibles() + ",\"reloj\":" + getReloj() + "}";
            s.respuestas.put(idPeticion, json);
            s.tocar();
            responder(ex, 200, json);
        }
    }

    private void salir(HttpExchange ex, Map<String, String> p) throws IOException {
        String idSesion = ex.getRequestHeaders().getFirst("X-Sesion");
        Sesion s = idSesion == null ? null : sesiones.remove(idSesion);
        int relojV = leerReloj(p.get("reloj"));

        int r = actualizarConMensaje(relojV);
        if (s != null) {
            System.out.println("[" + idNodo + "][reloj " + r + "][cliente #" + s.idCliente + " " + s.nombre + "] Terminó su turno.");
            liberarCupo();
        } else {
            System.out.println("[" + idNodo + "][reloj " + r + "] Salida repetida o sesión vencida: no hay cupo que liberar.");
        }
        responder(ex, 200, "{\"estado\":\"ok\",\"reloj\":" + r + "}");
    }

    private void estado(HttpExchange ex, Map<String, String> p) throws IOException {
        responder(ex, 200, "{\"idNodo\":\"" + idNodo + "\",\"evento\":\"" + evento + "\",\"disponibles\":" + getDisponibles() + ",\"sesiones\":" + sesiones.size() + ",\"reloj\":" + getReloj() + "}");
    }

    private void expirarSesiones() {
        long ahora = System.currentTimeMillis();
        for (Map.Entry<String, Sesion> e : sesiones.entrySet()) {
            Sesion s = e.getValue();
            if (ahora - s.ultimaActividad > LEASE_MS && sesiones.remove(e.getKey(), s)) {
                liberarCupo();
                int r = eventoLocal();
                System.out.println("[" + idNodo + "][reloj " + r + "][cliente #" + s.idCliente + " " + s.nombre + "] Lease vencido: se libera su lugar por inactividad.");
            }
        }
    }

    public static void main(String[] args) throws IOException {
        int boletos = args.length > 0 ? Integer.parseInt(args[0]) : 10;
        int maxClientes = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        int puerto = args.length > 2 ? Integer.parseInt(args[2]) : 5000;
        String idNodo = args.length > 3 ? args[3] : "N1";
        String evento = "Concierto de opera";

        Servidor servidor = new Servidor(idNodo, evento, boletos, maxClientes);

        ThreadPoolExecutor pool = new ThreadPoolExecutor(HILOS, HILOS, 0L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(COLA), new ThreadPoolExecutor.AbortPolicy());

        ScheduledExecutorService limpiador = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "limpiador-de-sesiones");
            t.setDaemon(true);
            return t;
        });
        limpiador.scheduleAtFixedRate(() -> {
            try {
                servidor.expirarSesiones();
            } catch (Throwable t) {
                System.out.println("[" + idNodo + "] Error en el limpiador: " + t);
            }
        }, 1, 1, TimeUnit.SECONDS);

        HttpServer http = HttpServer.create(new InetSocketAddress(puerto), 0);
        http.setExecutor(pool);
        ruta(http, "/conectar", "POST", servidor::conectar);
        ruta(http, "/vender", "POST", servidor::vender);
        ruta(http, "/salir", "POST", servidor::salir);
        ruta(http, "/estado", "GET", servidor::estado);
        http.start();

        System.out.println();
        System.out.println("[" + idNodo + "] Taquilla iniciada en http://localhost:" + puerto);
        System.out.println("[" + idNodo + "] Evento: '" + evento + "'");
        System.out.println("[" + idNodo + "] Boletos disponibles: " + boletos);
        System.out.println("[" + idNodo + "] Vendedores simultáneos permitidos: " + maxClientes);
        System.out.println("[" + idNodo + "] Hilos: " + HILOS + ", cola: " + COLA + ", lease: " + (LEASE_MS / 1000) + " s");
        System.out.println("[" + idNodo + "] Autenticación: Bearer token" + (TOKEN.equals(TOKEN_DEFECTO) ? " (token por defecto: cámbielo con -Dtoken=...)" : ""));
        System.out.println("[" + idNodo + "] Esperando vendedores...\n");
    }
}