import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

public class Servidor {
    private final String idNodo;
    private final String evento;
    private int disponibles;
    private int reloj;
    private final Semaphore cupos;
    private final AtomicInteger contadorClientes = new AtomicInteger(0);
    private final Set<Integer> clientesConCupo = ConcurrentHashMap.newKeySet();

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
    public String getEvento() { return evento; }
    public String getIdNodo() { return idNodo; }

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

    private interface Accion { void ejecutar(HttpExchange ex, Map<String, String> p) throws IOException; }

    private static void ruta(HttpServer http, String camino, String metodo, Accion accion) {
        http.createContext(camino, ex -> {
            try {
                if (!ex.getRequestMethod().equalsIgnoreCase(metodo)) {
                    responder(ex, 405, "{\"error\":\"Use el metodo " + metodo + "\"}");
                    return;
                }
                accion.ejecutar(ex, parametros(ex));
            } catch (NumberFormatException e) {
                responder(ex, 400, "{\"error\":\"Parametro numerico invalido\"}");
            } finally {
                ex.close();
            }
        });
    }

    private void conectar(HttpExchange ex, Map<String, String> p) throws IOException {
        String nombre = p.get("nombre");
        if (nombre == null || nombre.isBlank()) {
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

        clientesConCupo.add(idCliente);
        System.out.println("[" + idNodo + "][cliente #" + idCliente + "] Atendiendo a " + nombre);
        responder(ex, 200, "{\"estado\":\"ok\",\"idCliente\":" + idCliente + ",\"reloj\":" + r + "}");
    }

    private void vender(HttpExchange ex, Map<String, String> p) throws IOException {
        String nombre = p.getOrDefault("nombre", "");
        int idCliente = Integer.parseInt(p.get("idCliente"));
        int relojV = Integer.parseInt(p.get("reloj"));

        if (!clientesConCupo.contains(idCliente)) {
            responder(ex, 403, "{\"error\":\"El cliente no tiene un lugar en esta taquilla. Use /conectar primero\"}");
            return;
        }

        boolean vendido = vender(nombre, idCliente, relojV);
        int relojR = getReloj();
        int restantes = getDisponibles();
        responder(ex, 200, "{\"estado\":\"" + (vendido ? "ok" : "agotado") + "\",\"disponibles\":" + restantes + ",\"reloj\":" + relojR + "}");
    }

    private void salir(HttpExchange ex, Map<String, String> p) throws IOException {
        String nombre = p.getOrDefault("nombre", "");
        int idCliente = Integer.parseInt(p.get("idCliente"));
        int relojV = Integer.parseInt(p.get("reloj"));

        int r = actualizarConMensaje(relojV);
        System.out.println("[" + idNodo + "][reloj " + r + "][cliente #" + idCliente + " " + nombre + "] Terminó su turno.");

        if (clientesConCupo.remove(idCliente)) {
            liberarCupo();
        }
        responder(ex, 200, "{\"estado\":\"ok\",\"reloj\":" + r + "}");
    }

    private void estado(HttpExchange ex, Map<String, String> p) throws IOException {
        responder(ex, 200, "{\"idNodo\":\"" + idNodo + "\",\"evento\":\"" + evento + "\",\"disponibles\":" + getDisponibles() + ",\"reloj\":" + getReloj() + "}");
    }

    public static void main(String[] args) throws IOException {
        int boletos = args.length > 0 ? Integer.parseInt(args[0]) : 10;
        int maxClientes = args.length > 1 ? Integer.parseInt(args[1]) : 2;
        int puerto = args.length > 2 ? Integer.parseInt(args[2]) : 5000;
        String idNodo = args.length > 3 ? args[3] : "N1";
        String evento = "Concierto de opera";

        Servidor servidor = new Servidor(idNodo, evento, boletos, maxClientes);

        HttpServer http = HttpServer.create(new InetSocketAddress(puerto), 0);
        http.setExecutor(Executors.newCachedThreadPool());
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
        System.out.println("[" + idNodo + "] Esperando vendedores...\n");
    }
}
