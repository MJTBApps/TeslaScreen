# TeslaScreen (nombre provisional)

App para ver la pantalla del móvil en el navegador de un Tesla, por el punto de acceso del propio móvil. Proyecto independiente de OpoAlert y sin relación con Tesla, Inc.

> El nombre definitivo **no puede llevar «Tesla»**: el App Store lo rechaza (regla 4.1(c)) y es una marca registrada. Hay que elegir otro antes de publicar.

## Qué queremos mejorar respecto a TslaMirror

Elegido por el usuario el 08/11/2026:

| Limitación de TslaMirror | Qué haremos | ¿Se puede? |
|---|---|---|
| Solo aparcado | Dibujar el vídeo en un canvas (WebCodecs) en vez de en una etiqueta de vídeo, que es la que Tesla pausa al salir de P | **Sí: comprobado en el coche el 09/11/2026** (A sigue circulando; B y C se paran) |
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

### Resultados en el coche (09/11/2026, Model 3 2026)

- Chrome **148**, Linux x86_64. Pantalla 1254×784, ventana del navegador 773×601 (escala 1,53).
- WebCodecs (decodificador y codificador), H.264 Baseline y High, H.265, Media Source, WebSocket, WebRTC, WebGL, pantalla completa, Wake Lock y eventos táctiles: **todo sí**.
- Aparcado: A a 30 fps con 2,8 ms de decodificación; B y C en movimiento.
- **Al circular, A (canvas) sigue moviéndose y B y C (etiquetas de vídeo) se paran.** El registro no anotó ninguna congelación ni pausa de B y C: el coche deja de dibujar el vídeo sin pausarlo (su `currentTime` sigue avanzando), así que el vigilante no lo ve. Lo vio el usuario.
- `http://172.20.10.1` con el punto de acceso del iPhone: **ERR_CONNECTION_REFUSED**. El navegador sí llega al iPhone (rechaza la conexión porque no hay nada escuchando); no bloquea la dirección privada.

**Consecuencias para F1:**
- Usar siempre canvas + WebCodecs (nunca `<video>`, tampoco para WebRTC).
- WebCodecs solo funciona en una página segura (HTTPS). Una página servida por `http://172.20.10.1` no lo tendría, y una página HTTPS no puede abrir `ws://` hacia el iPhone (contenido mixto). Opciones a probar en F1: (a) página en GitHub Pages (HTTPS) y vídeo por **canal de datos de WebRTC** hasta el iPhone, que sí está permitido; (b) túnel VPN local con dominio y certificado, como TslaMirror. Primero (a), que no necesita servidor ni certificado.

### F1, paso 1: prueba de conexión con el iMac (ahora)

Antes de compilar la app de iPhone (sin Xcode aquí, cada intento pasa por TestFlight), el iMac hace de emisor: `herramientas/emisor_prueba.py` manda el vídeo de prueba como lo mandará la app, y `docs/conexion.html` prueba en el coche cada camino:

| Camino | Qué decide |
|---|---|
| fetch `http://IP:8765/flujo` desde la página https de GitHub Pages | Si Chrome 148 del coche deja hablar con la red local (permiso «Local Network Access»). Si funciona, la app solo necesita un servidor HTTP: lo más sencillo |
| WebSocket `ws://` | Se espera que falle (contenido mixto) |
| WebSocket `wss://` con certificado propio | Plan B: aceptar una vez el aviso del certificado |
| Página abierta directamente en `http://IP:8765/` | Confirma si sin HTTPS desaparece WebCodecs |

Si todo falla, quedan WebRTC sin servidor de señalización o el túnel VPN de TslaMirror.

Probarlo en el iPhone (lo recomendado; es la conexión real de la app): instalar **a-Shell** (gratis, App Store), y en ella:

```
curl -L -O https://raw.githubusercontent.com/MJTBApps/TeslaScreen/main/herramientas/emisor_prueba.py
python3 emisor_prueba.py
```

El emisor baja solo la página y el vídeo de la web. Con el coche en el punto de acceso del iPhone, la IP es siempre `172.20.10.1` (la página la trae escrita). a-Shell tiene que quedarse delante y con la pantalla encendida (iOS para las apps en segundo plano).

Probarlo en el iMac, `python3 herramientas/emisor_prueba.py` (si macOS pregunta, permitir conexiones entrantes). Con el coche en la misma Wi-Fi, abrir `https://mjtbapps.github.io/TeslaScreen/conexion.html`, escribir la IP que muestra el iMac y pulsar **Probar**. Comprobado en local el 09/11/2026: fetch y WebSocket a 30 fps, 1 Mbps, 4 ms de ida y vuelta.

## App de Android (`android/`, desde el 10/11/2026)

Nombre provisional **EspejoCoche** (`es.mjtbapps.espejocoche`, sin «Tesla» por las normas de Google Play). Primera versión de prueba, 0.1.0:

- `CaptureService.kt`: MediaProjection → H.264 por hardware (MediaCodec, lado mayor 1280, 4 Mbps, fotograma clave cada 2 s, repite el último a los 100 ms si la pantalla no cambia). Al girar el móvil se reconfigura (Android 14+ solo admite una pantalla virtual por permiso).
- `VideoServer.kt`: servidor en el puerto 8765 con el mismo protocolo que `herramientas/emisor_prueba.py` (`/ping`, `/flujo`, `/ws`; paquete = longitud + clave + marca de tiempo en µs + H.264).
- `MainActivity.kt`: muestra las direcciones para el coche (la del punto de acceso primero) y el estado.
- Página del coche: `docs/ver.html?ip=…` (pantalla completa, reconexión automática, fetch y si no WebSocket, fps, Mbps y retraso real captura → pantalla).

Compilar: `cd android && JAVA_HOME=/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home sh gradlew --no-daemon :app:assembleDebug` (copiar antes `.gradle-dist` de OpoAlert o dejar que lo descargue). La APK de prueba se publica en `docs/EspejoCoche-0.2.1-prueba.apk` (firma de depuración del iMac).

Comprobado en el emulador (Android 14) el 10/11/2026: permiso, emisión, giro a horizontal y página en vivo (10–20 fps y 100–150 ms en el emulador, que codifica por software). Falta probarlo en un móvil real y en el coche.

0.1.1 (11/11/2026): en el primer móvil real la grabación se paraba al segundo de empezar, sin decir por qué. Ahora la app guarda un registro de cada paso y de cualquier cierre (`Diario.kt`) y lo muestra en pantalla con el modelo del móvil; el compresor prueba varios ajustes (de 1280 con Baseline a 720 sin extras) y el giro ya no puede cerrar la app. Comprobado en el emulador de Android 16 con «Compartir toda la pantalla».

0.1.2 (11/11/2026): primer móvil real, **Xiaomi Redmi Note 10 5G (M2103K19G, MediaTek, Android 11)**. Su compresor `OMX.MTK.VIDEO.ENCODER.AVC` rechaza el perfil Baseline y alguno de los extras (repetir fotograma, sin B, baja latencia): ahora se prueban de uno en uno. Como sin «repetir fotograma» la pantalla quieta no manda nada, el servidor guarda los fotogramas desde el último clave y se los manda al coche que se conecta (marcados como repetidos, bit 2 del byte de banderas), y `ver.html` conserva la última imagen («pantalla quieta»).

0.1.3–0.1.5 (11/11/2026): diagnóstico en el coche. **El Tesla no deja que su navegador conecte con ningún aparato de su propia wifi** (ERR_CONNECTION_REFUSED en 8765, 80, 443, 8080, 8443, 8000, 8888, 3000 y 5000, mientras un iPhone en la misma wifi sí llega). El Xiaomi usa `ap0` con IP 10.x para su punto de acceso. La conexión local queda descartada.

Prueba WebRTC (11–12/11/2026, `docs/rtc-ver.html` + `docs/rtc-emisor.html`, cámara del móvil): sin servidor de reenvío falla; **con TURN de ExpressTURN (plan gratuito, 1.000 GB/mes, puerto 3478) funciona aparcado y en marcha** (13 fps con poca luz, red 210 ms, el Tesla sale por UDP y solo el móvil usa el reenvío). El emparejamiento usa el servidor público de PeerJS. Los datos de TURN se escriben en el móvil y no están en el repositorio.

0.2.0 (12/11/2026): la app envía la **pantalla por WebRTC** (`stream-webrtc-android` 1.3.10, `ScreenCapturerAndroid`) y se empareja con `rtc-ver.html` por el código de 6 cifras (`Senal.kt`, protocolo de PeerJS). Datos de TURN en la propia app (se guardan solo en el móvil). Aviso de responsabilidad al abrirla la primera vez. Solo arm64-v8a y x86_64. Comprobado en el emulador hasta la respuesta del coche (sin TURN el emulador no conecta).

0.2.1 (13/11/2026), tras la primera prueba real (funciona): **gira a horizontal** (`Capturador.kt`, una sola pantalla virtual con `resize`; antes los vídeos horizontales salían diminutos), **pantalla del móvil encendida** mientras emite (`SCREEN_DIM_WAKE_LOCK`), `rtc-ver.html` pide retraso mínimo de reproducción (`playoutDelayHint`/`jitterBufferTarget` = 0) y se corrige un cierre al terminar (excepciones en llamadas nativas de WebRTC). Pendiente: control táctil, sonido por Bluetooth adelantado a la imagen, Google Maps no se ve al grabar (Waze sí).

Pendiente para Google Play: control táctil (servicio de accesibilidad), aviso al detectar el Bluetooth del coche, favorito con código y servidor de emparejamiento, nombre e icono definitivos.

## Siguientes fases

- **F1** (2–3 días): app mínima de iPhone que sirve la página y un vídeo de prueba por el punto de acceso. Comprueba IP privada frente a túnel VPN y la latencia real.
- **F2**: captura real con ReplayKit, reconexión automática y modo horizontal con recorte.
- **F3**: Android (MediaProjection y control táctil).

En este iMac no hay Xcode (macOS 13), así que la app de iPhone se compilará en GitHub Actions y se instalará por TestFlight, como OpoAlert. Hará falta un identificador de app nuevo en App Store Connect.

## Herramientas

- `herramientas/generar_video_prueba.py` crea los vídeos de `docs/prueba/` (necesita `pip install av numpy`).
- `herramientas/emisor_prueba.py` es el emisor de prueba (solo biblioteca estándar y openssl; el certificado queda en `herramientas/.cert/`, que no se sube).
- Probar en local: `python3 herramientas/emisor_prueba.py` y abrir `http://localhost:8765/`.
