# TeslaScreen (nombre provisional)

App para ver la pantalla del móvil en el navegador de un Tesla, por el punto de acceso del propio móvil. Proyecto independiente de OpoAlert y sin relación con Tesla, Inc.

> El nombre definitivo **no puede llevar «Tesla»**: el App Store lo rechaza (regla 4.1(c)) y es una marca registrada. Hay que elegir otro antes de publicar.

## Qué queremos mejorar respecto a TslaMirror

Elegido por el usuario el 08/11/2026:

| Limitación de TslaMirror | Qué haremos | ¿Se puede? |
|---|---|---|
| Solo aparcado | Dibujar el vídeo en un canvas (WebCodecs) en vez de en una etiqueta de vídeo, que es la que Tesla pausa al salir de P | **Por probar en el coche** (prueba A frente a B y C) |
| Retraso y calidad | H.264 por hardware en el iPhone, decodificación por hardware en el coche, latencia medida y publicada | Sí |
| Imagen vertical | Recortar y ampliar una zona (p. ej. el mapa) para llenar la pantalla horizontal; las apps que giran se ven a pantalla completa | Sí |
| Precio o suscripción | Precio único, sin suscripción ni límite de minutos | Sí |
| Se corta al bloquear | Comprobar si la emisión de ReplayKit sigue con el iPhone bloqueado | Probablemente no en iOS (límite de Apple); en Android sí |
| Sin control táctil | En Android, con un servicio de accesibilidad que repite los toques del Tesla | **No en iOS** (Apple no deja controlar el iPhone desde fuera); sí en Android, más adelante |

## Cómo funcionará (iPhone)

1. Extensión de ReplayKit: captura la pantalla y la comprime en H.264 con VideoToolbox (límite de unos 50 MB de memoria: 720p a 30 fps).
2. El Tesla se conecta al punto de acceso del iPhone, que le da Internet.
3. La app sirve una página y el vídeo por WebSocket.
4. El navegador del Tesla decodifica con WebCodecs y dibuja en un canvas.
5. El sonido va por el Bluetooth del coche.

Problema abierto: el navegador del Tesla parece no abrir direcciones privadas (`172.20.10.x`). Soluciones conocidas: túnel VPN local en el iPhone con una IP «pública» virtual (como TslaMirror) o un dominio público.

## Fase 0: diagnóstico en el coche (ahora)

`docs/index.html` es una página que comprueba el navegador del coche: versión de Chrome, WebCodecs, Media Source, WebRTC, toques, y tres formas de mostrar vídeo (A canvas, B Media Source, C vídeo normal). Si alguna se congela, lo anota con la hora.

### Publicarla (una vez)

1. En GitHub Desktop: *File → Add local repository* → esta carpeta → *Publish repository* (puede ser pública; no contiene datos privados).
2. En github.com, en el repositorio: *Settings → Pages → Source: Deploy from a branch → main / docs*.
3. La página queda en `https://mjtbapps.github.io/<nombre-del-repositorio>/`.

### Prueba en el coche

1. Coche aparcado y conectado a Internet (Wi-Fi o datos del coche).
2. Abre la página en el navegador del Tesla y pulsa **Empezar las pruebas**. Haz una foto de la tabla «Navegador».
3. Pasa a **D** sin moverte y pulsa «Anotar ahora en D». Espera 30 s.
4. Con **otra persona conduciendo**, pulsa «Anotar ahora circulando» y espera 2 minutos.
5. Vuelve a P y haz una foto del registro y de los tres recuadros.
6. Extra: escribe `http://172.20.10.1` con el Tesla en el punto de acceso del iPhone y apunta el error que sale.

**Qué decide:** si A sigue en movimiento circulando, la app tiene sentido. Si B y C se paran pero A no, esa es nuestra ventaja sobre TslaMirror.

## Siguientes fases

- **F1** (2–3 días): app mínima de iPhone que sirve la página y un vídeo de prueba por el punto de acceso. Comprueba IP privada frente a túnel VPN y la latencia real.
- **F2**: captura real con ReplayKit, reconexión automática y modo horizontal con recorte.
- **F3**: Android (MediaProjection y control táctil).

En este iMac no hay Xcode (macOS 13), así que la app de iPhone se compilará en GitHub Actions y se instalará por TestFlight, como OpoAlert. Hará falta un identificador de app nuevo en App Store Connect.

## Herramientas

- `herramientas/generar_video_prueba.py` crea los vídeos de `docs/prueba/` (necesita `pip install av numpy`).
- Probar en local: `python3 -m http.server -d docs 8765` y abrir `http://localhost:8765/`.
