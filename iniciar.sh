#!/usr/bin/env bash
cd "$(dirname "$0")" || exit 1
mkdir -p out && javac -encoding UTF-8 -d out src/*.java || exit 1

java -cp out Sistema 20 5000 &
sleep 1
java -cp out Caja 2 5001 C1 localhost:5000 &
java -cp out Caja 2 5002 C2 localhost:5000 &
sleep 1
java -cp out Interfaz 8080 localhost:5000 localhost:5001 localhost:5002 &

trap 'kill $(jobs -p) 2>/dev/null' EXIT INT TERM
echo
echo "Abra http://localhost:8080 en el navegador. Ctrl+C detiene todo."
wait
