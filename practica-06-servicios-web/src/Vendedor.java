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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Vendedor {
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

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private static String post(Nodo nodo, String camino) throws IOException, InterruptedException {
        HttpRequest peticion = HttpRequest.newBuilder()
                .uri(URI.create("http://" + nodo.host + ":" + nodo.puerto + camino))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();
        HttpResponse<String> respuesta = HTTP.send(peticion, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (respuesta.statusCode() != 200) {
            throw new IOException("HTTP " + respuesta.statusCode() + " " + respuesta.body());
        }
        return respuesta.body();
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
                String rc = post(nodo, "/conectar?nombre=" + nombreUrl);
                reloj = Math.max(reloj, Integer.parseInt(campo(rc, "reloj"))) + 1;

                if (campo(rc, "estado").equalsIgnoreCase("nodo_lleno")) {
                    System.out.println("[reloj " + reloj + "][" + nombre + "] La taquilla " + nodo + " está ocupada.");
                    continue;
                }
                int idCliente = Integer.parseInt(campo(rc, "idCliente"));
                System.out.println("[reloj " + reloj + "][" + nombre + "] Conectado a la taquilla " + nodo + " como cliente #" + idCliente);

                while (intentosRestantes > 0) {
                    reloj++;
                    String rv = post(nodo, "/vender?idCliente=" + idCliente + "&nombre=" + nombreUrl + "&reloj=" + reloj);
                    String estado = campo(rv, "estado");
                    reloj = Math.max(reloj, Integer.parseInt(campo(rv, "reloj"))) + 1;

                    if (estado.equalsIgnoreCase("ok")) {
                        String restantes = campo(rv, "disponibles");
                        intentosRestantes--;
                        System.out.println("[reloj " + reloj + "][" + nombre + "] Boleto vendido en " + nodo + ". Quedan " + restantes + " boletos en esta taquilla. Le faltan comprar: " + intentosRestantes);
                    } else if (estado.equalsIgnoreCase("agotado")) {
                        System.out.println("[reloj " + reloj + "][" + nombre + "] La taquilla " + nodo + " se quedó sin boletos.");
                        break;
                    }
                }

                reloj++;
                String rs = post(nodo, "/salir?idCliente=" + idCliente + "&nombre=" + nombreUrl + "&reloj=" + reloj);
                reloj = Math.max(reloj, Integer.parseInt(campo(rs, "reloj"))) + 1;
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
