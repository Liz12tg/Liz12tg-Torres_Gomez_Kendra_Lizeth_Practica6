# Sistemas Distribuidos

Prácticas de la materia de Sistemas Distribuidos (ESCOM, IPN).
Todas parten del mismo escenario: una **boletería** para el *Concierto de ópera*,
con taquillas (nodos) que limitan cuántos vendedores atienden a la vez y un vendedor
que pasa de taquilla en taquilla hasta comprar todos sus boletos.

## Prácticas

| # | Carpeta | Tema |
|---|---------|------|
| 6 | [practica-06-servicios-web](practica-06-servicios-web/) | Servicio web REST sobre HTTP, con relojes lógicos de Lamport y semáforo de cupos |

## Requisitos

- JDK 17 o superior (`java -version` y `javac -version` deben coincidir).
- Git.

No se usa NetBeans, Maven ni librerías externas: se compila con `javac`.

## Convenciones

- Una carpeta por práctica: `practica-NN-tema/`.
- Dentro de cada una: `src/` (código), `README.md` (cómo compilar y ejecutar) y,
  opcionalmente, `docs/` (reporte).
- El código se guarda en UTF-8; se compila siempre con `-encoding UTF-8`.
- Lo compilado (`out/`) no se sube al repositorio.
