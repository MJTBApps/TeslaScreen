"""Emisor de prueba: hace en el iMac lo que hará la app del iPhone.

Sirve el vídeo de prueba (docs/prueba/prueba.h264) a 30 fps por tres caminos,
para ver cuál deja pasar el navegador del coche:

- http://IP:8765/flujo   vídeo por fetch (HTTP normal)
- ws://IP:8765/ws        vídeo por WebSocket
- https://IP:8766/...    lo mismo con un certificado propio (wss://IP:8766/ws)

También sirve docs/conexion.html en http://IP:8765/ y https://IP:8766/.

Cada paquete de vídeo es: 4 bytes de longitud (big endian) + 1 byte (1 = fotograma
clave) + el fotograma H.264 Annex B. Es el formato que usará la app.

Uso, con el coche en la misma Wi-Fi que el iMac:
    python3 herramientas/emisor_prueba.py

Solo usa la biblioteca estándar de Python y el openssl del sistema (para el
certificado de prueba, que se guarda en herramientas/.cert/ y no se sube).
"""

import base64
import hashlib
import http.server
import json
import socket
import ssl
import struct
import subprocess
import sys
import threading
import time
from pathlib import Path

AQUI = Path(__file__).resolve().parent
DOCS = AQUI.parent / "docs"
CERTS = AQUI / ".cert"
WEB = "https://mjtbapps.github.io/TeslaScreen/"
PUNTO_ACCESO_IPHONE = "172.20.10.1"


def preparar_docs():
    """Sin el repositorio al lado (p. ej. en a-Shell del iPhone), baja lo necesario de la web."""
    global DOCS
    if (DOCS / "prueba" / "prueba.h264").is_file():
        return
    import urllib.request
    DOCS = AQUI / "teslascreen_web"
    for nombre in ("conexion.html", "prueba/prueba.h264"):
        destino = DOCS / nombre
        if not destino.is_file():
            destino.parent.mkdir(parents=True, exist_ok=True)
            print("Bajando", WEB + nombre, flush=True)
            urllib.request.urlretrieve(WEB + nombre, destino)


preparar_docs()
PUERTO_HTTP, PUERTO_HTTPS, FPS = 8765, 8766, 30
GUID_WS = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
TIPOS = {".html": "text/html; charset=utf-8", ".js": "text/javascript", ".json": "application/json",
         ".h264": "video/h264", ".mp4": "video/mp4", ".css": "text/css"}


def ip_local():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("8.8.8.8", 80))  # no envía nada: solo elige la interfaz de salida
        return s.getsockname()[0]
    except OSError:
        return PUNTO_ACCESO_IPHONE
    finally:
        s.close()


def en_iphone():
    return sys.platform == "ios" or "iPhone" in " ".join(map(str, __import__("os").uname()))


def trocear(datos: bytes):
    """Separa H.264 Annex B en fotogramas (igual que la página)."""
    nals, i, inicio = [], 0, -1
    while i + 3 < len(datos):
        if datos[i] == 0 and datos[i + 1] == 0 and (datos[i + 2] == 1 or (datos[i + 2] == 0 and datos[i + 3] == 1)):
            if inicio >= 0:
                nals.append(inicio)
            inicio = i
            i += 3 if datos[i + 2] == 1 else 4
        else:
            i += 1
    if inicio >= 0:
        nals.append(inicio)
    fotos, desde, clave, con_corte = [], None, False, False
    for a in nals:
        cab = a + 3 if datos[a + 2] == 1 else a + 4
        tipo = datos[cab] & 0x1F
        es_corte = tipo in (1, 5)
        if con_corte and (6 <= tipo <= 9 or (es_corte and datos[cab + 1] & 0x80)):
            fotos.append((clave, datos[desde:a]))
            desde, clave, con_corte = None, False, False
        if desde is None:
            desde = a
        clave = clave or tipo == 5
        con_corte = con_corte or es_corte
    if con_corte:
        fotos.append((clave, datos[desde:]))
    return fotos


FOTOS = [struct.pack(">IB", len(d) + 1, 1 if c else 0) + d
         for c, d in trocear((DOCS / "prueba" / "prueba.h264").read_bytes())]


def emitir(escribir):
    """Manda los fotogramas en bucle a 30 fps hasta que el navegador se va."""
    t0, n = time.monotonic(), 0
    while True:
        escribir(FOTOS[n % len(FOTOS)])
        n += 1
        espera = t0 + n / FPS - time.monotonic()
        if espera > 0:
            time.sleep(espera)


def marco_ws(datos: bytes) -> bytes:
    n = len(datos)
    if n < 126:
        return bytes([0x82, n]) + datos
    if n < 65536:
        return bytes([0x82, 126]) + struct.pack(">H", n) + datos
    return bytes([0x82, 127]) + struct.pack(">Q", n) + datos


class Manejador(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, formato, *args):
        print(f"{time.strftime('%H:%M:%S')}  {self.client_address[0]}  {formato % args}", flush=True)

    def cors(self):
        # La página de GitHub Pages pide permiso para hablar con la red local.
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Private-Network", "true")
        self.send_header("Access-Control-Allow-Local-Network", "true")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.send_header("Cache-Control", "no-store")

    def do_OPTIONS(self):
        self.send_response(204)
        self.cors()
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_GET(self):
        ruta = self.path.split("?")[0]
        try:
            if ruta == "/ping":
                cuerpo = json.dumps({"emisor": "TeslaScreen", "t": time.time() * 1000}).encode()
                self.send_response(200)
                self.cors()
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(cuerpo)))
                self.end_headers()
                self.wfile.write(cuerpo)
            elif ruta == "/flujo":
                self.flujo()
            elif ruta == "/ws":
                self.websocket()
            else:
                self.archivo(ruta)
        except (BrokenPipeError, ConnectionResetError, ssl.SSLError, TimeoutError):
            pass  # el navegador ha cerrado la conexión

    def flujo(self):
        self.send_response(200)
        self.cors()
        self.send_header("Content-Type", "application/octet-stream")
        self.send_header("Transfer-Encoding", "chunked")
        self.end_headers()

        def escribir(datos):
            self.wfile.write(f"{len(datos):x}\r\n".encode() + datos + b"\r\n")
            self.wfile.flush()
        print(f"{time.strftime('%H:%M:%S')}  {self.client_address[0]}  empieza el vídeo por fetch", flush=True)
        emitir(escribir)

    def websocket(self):
        clave = self.headers.get("Sec-WebSocket-Key")
        if not clave or "websocket" not in self.headers.get("Upgrade", "").lower():
            self.send_error(400, "Falta la cabecera de WebSocket")
            return
        acepta = base64.b64encode(hashlib.sha1((clave + GUID_WS).encode()).digest()).decode()
        self.send_response(101)
        self.send_header("Upgrade", "websocket")
        self.send_header("Connection", "Upgrade")
        self.send_header("Sec-WebSocket-Accept", acepta)
        self.end_headers()
        self.close_connection = True

        def escribir(datos):
            self.wfile.write(marco_ws(datos))
            self.wfile.flush()
        print(f"{time.strftime('%H:%M:%S')}  {self.client_address[0]}  empieza el vídeo por WebSocket", flush=True)
        emitir(escribir)

    def archivo(self, ruta):
        if ruta in ("", "/"):
            ruta = "/conexion.html"
        destino = (DOCS / ruta.lstrip("/")).resolve()
        if not destino.is_file() or DOCS.resolve() not in destino.parents:
            self.send_error(404)
            return
        cuerpo = destino.read_bytes()
        self.send_response(200)
        self.cors()
        self.send_header("Content-Type", TIPOS.get(destino.suffix, "application/octet-stream"))
        self.send_header("Content-Length", str(len(cuerpo)))
        self.end_headers()
        self.wfile.write(cuerpo)


def certificado(ip):
    """Certificado propio para https://IP:8766 (solo para esta prueba)."""
    CERTS.mkdir(exist_ok=True)
    crt, key, marca = CERTS / "prueba.crt", CERTS / "prueba.key", CERTS / "ip.txt"
    if crt.exists() and key.exists() and marca.exists() and marca.read_text() == ip:
        return crt, key
    conf = CERTS / "openssl.cnf"
    conf.write_text(
        "[req]\ndistinguished_name=dn\nx509_extensions=ext\nprompt=no\n"
        "[dn]\nCN=TeslaScreen prueba\n"
        f"[ext]\nsubjectAltName=IP:{ip},DNS:localhost\nbasicConstraints=CA:FALSE\n"
    )
    subprocess.run(["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "30",
                    "-keyout", str(key), "-out", str(crt), "-config", str(conf)],
                   check=True, capture_output=True)
    marca.write_text(ip)
    return crt, key


def servidor(puerto, contexto=None):
    srv = http.server.ThreadingHTTPServer(("0.0.0.0", puerto), Manejador)
    srv.daemon_threads = True
    if contexto:
        srv.socket = contexto.wrap_socket(srv.socket, server_side=True)
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv


def main():
    # En el iPhone, el coche lo ve por el punto de acceso: siempre 172.20.10.1.
    ip = PUNTO_ACCESO_IPHONE if en_iphone() else ip_local()
    servidor(PUERTO_HTTP)
    try:
        crt, key = certificado(ip)
        ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
        ctx.load_cert_chain(crt, key)
        servidor(PUERTO_HTTPS, ctx)
        https = f"https://{ip}:{PUERTO_HTTPS}/"
    except (subprocess.CalledProcessError, OSError, ssl.SSLError) as e:
        https = f"(sin HTTPS: {e})"
    print(f"""
TeslaScreen · emisor de prueba ({len(FOTOS)} fotogramas en bucle a {FPS} fps)

  IP de este ordenador: {ip}
  En el coche abre:     https://mjtbapps.github.io/TeslaScreen/conexion.html
                        y escribe la IP {ip}

  Otras direcciones:    http://{ip}:{PUERTO_HTTP}/
                        {https}

Ctrl+C para parar.
""", flush=True)
    try:
        while True:
            time.sleep(3600)
    except KeyboardInterrupt:
        print("Parado.")
        sys.exit(0)


if __name__ == "__main__":
    main()
