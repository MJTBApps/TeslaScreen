"""Genera los vídeos de prueba de la página de diagnóstico.

Salida en docs/prueba/:
- prueba.h264: H.264 Annex B, perfil Constrained Baseline, 1280x720 a 30 fps,
  sin fotogramas B (el mismo formato que mandará la app del iPhone).
- prueba.mp4: el mismo vídeo en MP4 fragmentado, para Media Source Extensions
  y para la etiqueta <video>.

Cada fotograma lleva su número en grande y una barra que se mueve, para ver a
simple vista si la imagen se congela.

Uso (necesita PyAV y numpy):
    python herramientas/generar_video_prueba.py
"""

from pathlib import Path

import av
import numpy as np

ANCHO, ALTO, FPS, SEGUNDOS = 1280, 720, 30, 10
SALIDA = Path(__file__).resolve().parent.parent / "docs" / "prueba"

# Cifras de 5x7 puntos.
CIFRAS = {
    "0": ["01110", "10001", "10011", "10101", "11001", "10001", "01110"],
    "1": ["00100", "01100", "00100", "00100", "00100", "00100", "01110"],
    "2": ["01110", "10001", "00001", "00010", "00100", "01000", "11111"],
    "3": ["11110", "00001", "00001", "01110", "00001", "00001", "11110"],
    "4": ["00010", "00110", "01010", "10010", "11111", "00010", "00010"],
    "5": ["11111", "10000", "11110", "00001", "00001", "10001", "01110"],
    "6": ["00110", "01000", "10000", "11110", "10001", "10001", "01110"],
    "7": ["11111", "00001", "00010", "00100", "01000", "01000", "01000"],
    "8": ["01110", "10001", "10001", "01110", "10001", "10001", "01110"],
    "9": ["01110", "10001", "10001", "01111", "00001", "00010", "01100"],
}


def dibujar_numero(img, texto, x, y, punto, color):
    for i, c in enumerate(texto):
        for fila, bits in enumerate(CIFRAS[c]):
            for col, bit in enumerate(bits):
                if bit == "1":
                    x0 = x + (i * 6 + col) * punto
                    y0 = y + fila * punto
                    img[y0:y0 + punto - 2, x0:x0 + punto - 2] = color


def fotograma(n):
    img = np.zeros((ALTO, ANCHO, 3), dtype=np.uint8)
    img[:] = (24, 28, 34)
    # Barra que cruza la pantalla una vez por segundo.
    x = int((n % FPS) / FPS * (ANCHO - 60))
    img[:, x:x + 60] = (234, 88, 12)
    # Cuadrícula de referencia.
    img[::80, :] = (60, 66, 74)
    img[:, ::80] = (60, 66, 74)
    dibujar_numero(img, f"{n:04d}", 340, 240, 24, (240, 240, 240))
    return img


def codificar(contenedor, formato, opciones):
    salida = av.open(str(SALIDA / contenedor), "w", format=formato, options=opciones)
    flujo = salida.add_stream("libx264", rate=FPS)
    flujo.width, flujo.height, flujo.pix_fmt = ANCHO, ALTO, "yuv420p"
    flujo.bit_rate = 1_500_000
    flujo.options = {
        "profile": "baseline",
        "level": "3.1",
        "g": str(FPS),
        "bf": "0",
        "tune": "zerolatency",
        "preset": "veryfast",
    }
    for n in range(FPS * SEGUNDOS):
        frame = av.VideoFrame.from_ndarray(fotograma(n), format="rgb24")
        for paquete in flujo.encode(frame):
            salida.mux(paquete)
    for paquete in flujo.encode():
        salida.mux(paquete)
    salida.close()


if __name__ == "__main__":
    SALIDA.mkdir(parents=True, exist_ok=True)
    codificar("prueba.h264", "h264", {})
    codificar("prueba.mp4", "mp4", {"movflags": "frag_keyframe+empty_moov+default_base_moof"})
    for f in ("prueba.h264", "prueba.mp4"):
        print(f, (SALIDA / f).stat().st_size // 1024, "KB")
