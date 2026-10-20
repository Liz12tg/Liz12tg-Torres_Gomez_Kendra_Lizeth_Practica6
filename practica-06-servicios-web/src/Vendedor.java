import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Vendedor {
    private static final String TOKEN = System.getProperty("token", "boleteria-2026");
    private static final int REINTENTOS = 3;

    private static class Nodo {
        final String host;
        final int puerto;

        Nodo(String host, int puerto) {
            this.host = host;
            this.puerto = puerto;
        }

        @Override
        public String toString() { return host + ":" + puerto; }
    }

    private static class ErrorHttp extends IOException {
        final int codigo;

        ErrorHttp(int codigo, String cuerpo) {
            super("HTTP " + codigo + " " + cuerpo);
            this.codigo = codigo;
        }
    }

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static String post(Nodo nodo, String camino, String sesion) throws IOException, InterruptedException {
        HttpRequest.Builder peticion = HttpRequest.newBuilder()
                .uri(URI.create("http://" + nodo.host + ":" + nodo.puerto + camino))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + TOKEN)
                .POST(HttpRequest.BodyPublishers.noBody());
        if (sesion != null) {
            peticion.header("X-Sesion", sesion);
        }
        HttpResponse<String> respuesta = HTTP.send(peticion.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (respuesta.statusCode() != 200) {
            throw new ErrorHttp(respuesta.statusCode(), respuesta.body());
        }
        return respuesta.body();
    }

    private static String postConReintentos(Nodo nodo, String camino, String sesion, String nombre) throws IOException, InterruptedException {
        for (int intento = 1; ; intento++) {
            try {
                return post(nodo, camino, sesion);
            } catch (ErrorHttp e) {
                if (e.codigo < 500 || intento == REINTENTOS) { throw e; }
            } catch (IOException e) {
                if (intento == REINTENTOS) { throw e; }
            }
            System.out.println("[" + nombre + "] Sin respuesta de " + nodo + ". Reintento " + intento + " de " + (REINTENTOS - 1) + " con la misma petición.");
            Thread.sleep(200L << (intento - 1));
        }
    }

    private static String campo(String json, String nombre) {
        Matcher m = Pattern.compile("\"" + nombre + "\"\\s*:\\s*\"?([^\",}]*)\"?").matcher(json);
        return m.find() ? m.group(1) : "";
    }

    public static void main(String[] args) {
        String nombre = args.length > 0 ? args[0] : "Vendedor";
        int intentosRestantes = args.length > 1 ? Integer.parseInt(args[1]) : 6;
        List<Nodo> nodos = new ArrayList<>();

        for (int i = 2; i < args.length; i++) {
            String[] hp = args[i].split(":");
            if (hp.length == 2) {
                nodos.add(new Nodo(hp[0], Integer.parseInt(hp[1])));
            }
        }
        if (nodos.isEmpty()) {
            nodos.add(new Nodo("localhost", 5000));
        }

        int reloj = 0;
        String nombreUrl = URLEncoder.encode(nombre, StandardCharsets.UTF_8);

        for (Nodo nodo : nodos) {
            if (intentosRestantes <= 0) { break; }
            System.out.println();
            System.out.println("[" + nombre + "] Intentando comprar en la taquilla " + nodo);
            try {
                reloj++;
                String rc = post(nodo, "/conectar?nombre=" + nombreUrl, null);
                reloj = Math.max(reloj, Integer.parseInt(campo(rc, "reloj"))) + 1;

                if (campo(rc, "estado").equalsIgnoreCase("nodo_lleno")) {
                    System.out.println("[reloj " + reloj + "][" + nombre + "] La taquilla " + nodo + " está ocupada.");
                    continue;
                }
                int idCliente = Integer.parseInt(campo(rc, "idCliente"));
                String sesion = campo(rc, "sesion");
                System.out.println("[reloj " + reloj + "][" + nombre + "] Conectado a la taquilla " + nodo + " como cliente #" + idCliente);

                while (intentosRestantes > 0) {
                    reloj++;
                    String idPeticion = UUID.randomUUID().toString();
                    String rv = postConReintentos(nodo, "/vender?reloj=" + reloj + "&idPeticion=" + idPeticion, sesion, nombre);
                    String estado = campo(rv, "estado");
                    reloj = Math.max(reloj, Integer.parseInt(campo(rv, "reloj"))) + 1;

                    if (estado.equalsIgnoreCase("ok")) {
                        String restantes = campo(rv, "disponibles");
                        intentosRestantes--;
                        String nota = campo(rv, "repetida").equals("true") ? " (respuesta repetida: no se vendió otro boleto)" : "";
                        System.out.println("[reloj " + reloj + "][" + nombre + "] Boleto vendido en " + nodo + ". Quedan " + restantes + " boletos en esta taquilla. Le faltan comprar: " + intentosRestantes + nota);
                    } else if (estado.equalsIgnoreCase("agotado")) {
                        System.out.println("[reloj " + reloj + "][" + nombre + "] La taquilla " + nodo + " se quedó sin boletos.");
                        break;
                    }
                }

                reloj++;
                String rs = postConReintentos(nodo, "/salir?reloj=" + reloj, sesion, nombre);
                reloj = Math.max(reloj, Integer.parseInt(campo(rs, "reloj"))) + 1;
            } catch (ErrorHttp e) {
                System.out.println("[reloj " + reloj + "][" + nombre + "] La taquilla " + nodo + " rechazó la petición (" + e.getMessage() + "). Se probará la siguiente taquilla.");
            } catch (IOException | InterruptedException | NumberFormatException e) {
                System.out.println("[reloj " + reloj + "][" + nombre + "] No se pudo conectar con " + nodo + ". Se probará la siguiente taquilla.");
            }
        }

        System.out.println();
        if (intentosRestantes == 0) {
            System.out.println("[" + nombre + "] Terminó su turno. Compró todos los boletos solicitados.");
        } else {
            System.out.println("[" + nombre + "] Terminó su turno. Boletos que faltó comprar: " + intentosRestantes);
        }
    }
}