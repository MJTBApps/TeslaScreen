package es.mjtbapps.espejocoche

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONObject
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpParameters
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.ScreenCapturerAndroid
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Captura la pantalla y la envía al navegador del coche por WebRTC, a través de
 * Internet: el coche no deja conectar con aparatos de su propia wifi, pero sí
 * recibir WebRTC (comprobado aparcado y en marcha).
 *
 * El emparejamiento va por el servidor público de PeerJS ([Senal]) con el código
 * de 6 cifras que muestra rtc-ver.html, y el vídeo por el servidor de reenvío (TURN).
 */
class CaptureService : Service() {

    companion object {
        const val EXTRA_CODIGO = "codigo"
        const val EXTRA_DATOS = "datos"
        const val EXTRA_COCHE = "coche"
        const val ACCION_PARAR = "es.mjtbapps.espejocoche.PARAR"
        private const val LADO_MAYOR = 1280

        @Volatile var activo = false
        @Volatile var estado = "Parado"
    }

    private lateinit var hilo: HandlerThread
    private lateinit var manejador: Handler
    private var egl: EglBase? = null
    private var fabrica: PeerConnectionFactory? = null
    private var capturador: ScreenCapturerAndroid? = null
    private var ayudante: SurfaceTextureHelper? = null
    private var fuente: VideoSource? = null
    private var pista: VideoTrack? = null
    private var conexion: PeerConnection? = null
    private var senal: Senal? = null
    private var bloqueo: PowerManager.WakeLock? = null
    private var coche = ""
    private val idConexion = "mc_" + UUID.randomUUID().toString().take(12)
    private var ancho = 0
    private var alto = 0
    private var ultimosBytes = 0L
    private var ultimosFotos = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_PARAR) {
            Diario.anotar("Parado por el usuario")
            stopSelf()
            return START_NOT_STICKY
        }
        if (activo) return START_NOT_STICKY
        // Android 14+: primero el servicio en primer plano, luego la proyección.
        startForeground(1, notificacion(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        val codigo = intent?.getIntExtra(EXTRA_CODIGO, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val datos = intent?.getParcelableExtra<Intent>(EXTRA_DATOS)
        coche = intent?.getStringExtra(EXTRA_COCHE) ?: ""
        if (codigo != Activity.RESULT_OK || datos == null || coche.isEmpty()) {
            Diario.anotar("El servicio no ha recibido el permiso o el código del coche")
            stopSelf()
            return START_NOT_STICKY
        }
        activo = true
        estado = "Arrancando…"
        hilo = HandlerThread("rtc").also { it.start() }
        manejador = Handler(hilo.looper)
        bloqueo = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EspejoCoche:emision").also { it.acquire(4 * 60 * 60 * 1000L) }
        manejador.post { arrancar(datos) }
        return START_NOT_STICKY
    }

    private fun fallo(texto: String) {
        Diario.anotar("ERROR $texto")
        estado = "Error: $texto"
        stopSelf()
    }

    private fun arrancar(datos: Intent) {
        try {
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(this).createInitializationOptions())
            val e = EglBase.create().also { egl = it }
            val f = PeerConnectionFactory.builder()
                .setVideoEncoderFactory(DefaultVideoEncoderFactory(e.eglBaseContext, true, true))
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(e.eglBaseContext))
                .createPeerConnectionFactory()
            fabrica = f

            val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
            val r = wm.maximumWindowMetrics.bounds
            escalar(r.width(), r.height())
            Diario.anotar("Pantalla del móvil: ${r.width()}×${r.height()} → envío $ancho×$alto")

            val cap = ScreenCapturerAndroid(datos, object : MediaProjection.Callback() {
                override fun onStop() { Diario.anotar("Android ha parado la grabación"); stopSelf() }
            })
            capturador = cap
            val ay = SurfaceTextureHelper.create("captura", e.eglBaseContext).also { ayudante = it }
            val fu = f.createVideoSource(true).also { fuente = it }
            cap.initialize(ay, this, fu.capturerObserver)
            cap.startCapture(ancho, alto, 30)
            pista = f.createVideoTrack("pantalla", fu)
            Diario.anotar("Captura en marcha")

            estado = "Buscando el coche (código $coche)…"
            senal = Senal(
                alAbrir = { manejador.post { llamar() } },
                alMensaje = { tipo, p -> manejador.post { mensaje(tipo, p) } },
                alFallar = { t -> manejador.post { fallo(t) } },
            ).also { it.conectar() }
            manejador.post(estadistica)
        } catch (e: Exception) {
            Diario.error("al arrancar", e)
            estado = "Error: ${e.message}"
            stopSelf()
        }
    }

    private fun escalar(w: Int, h: Int) {
        val f = minOf(1.0, LADO_MAYOR.toDouble() / max(w, h))
        fun r16(x: Double) = max(16, (x / 16).roundToInt() * 16)
        ancho = r16(w * f); alto = r16(h * f)
    }

    private fun servidoresIce(): List<PeerConnection.IceServer> {
        val l = mutableListOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())
        val (u, p) = Ajustes.turn(this)
        if (u.isNotEmpty() && p.isNotEmpty()) {
            l += PeerConnection.IceServer.builder(listOf("turn:free.expressturn.com:3478", "turn:free.expressturn.com:3478?transport=tcp"))
                .setUsername(u).setPassword(p).createIceServer()
        } else {
            Diario.anotar("Sin datos del servidor de reenvío: probablemente no conecte")
        }
        return l
    }

    /** El móvil llama al coche (PeerJS «call»): crea la oferta y la manda por la señalización. */
    private fun llamar() {
        val f = fabrica ?: return
        Diario.anotar("Emparejamiento abierto; llamando al coche $coche")
        val cfg = PeerConnection.RTCConfiguration(servidoresIce()).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }
        val pc = f.createPeerConnection(cfg, observador) ?: return fallo("no se pudo crear la conexión WebRTC")
        conexion = pc
        val emisor = pc.addTrack(pista, listOf("espejo"))
        // Pantalla: mejor bajar fotogramas que resolución (texto de mapas legible).
        emisor.parameters = emisor.parameters.apply {
            degradationPreference = RtpParameters.DegradationPreference.MAINTAIN_RESOLUTION
            encodings.forEach { it.maxBitrateBps = 3_000_000; it.maxFramerate = 30 }
        }
        pc.createOffer(object : Sdp() {
            override fun onCreateSuccess(d: SessionDescription) {
                pc.setLocalDescription(Sdp(), d)
                val sdp = JSONObject().put("type", "offer").put("sdp", d.description)
                senal?.enviar("OFFER", "espejocoche-$coche", base().put("sdp", sdp).put("browser", "android"))
            }
            override fun onCreateFailure(e: String?) = fallo("oferta: $e")
        }, MediaConstraints())
    }

    private fun base() = JSONObject().put("type", "media").put("connectionId", idConexion)

    private fun mensaje(tipo: String, p: JSONObject) {
        val pc = conexion ?: return
        when (tipo) {
            "ANSWER" -> {
                val s = p.optJSONObject("sdp") ?: return
                pc.setRemoteDescription(Sdp(), SessionDescription(SessionDescription.Type.ANSWER, s.getString("sdp")))
                Diario.anotar("El coche ha contestado")
                estado = "Conectando con el coche…"
            }
            "CANDIDATE" -> {
                val c = p.optJSONObject("candidate") ?: return
                pc.addIceCandidate(IceCandidate(c.optString("sdpMid"), c.optInt("sdpMLineIndex"), c.getString("candidate")))
            }
        }
    }

    private val observador = object : PeerConnection.Observer {
        override fun onIceCandidate(c: IceCandidate) {
            val j = JSONObject().put("candidate", c.sdp).put("sdpMid", c.sdpMid).put("sdpMLineIndex", c.sdpMLineIndex)
            senal?.enviar("CANDIDATE", "espejocoche-$coche", base().put("candidate", j))
        }
        override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {
            Diario.anotar("Conexión: $s")
            when (s) {
                PeerConnection.IceConnectionState.FAILED -> estado = "No se ha podido conectar (revisa el servidor de reenvío)"
                PeerConnection.IceConnectionState.DISCONNECTED -> estado = "Conexión perdida, reintentando…"
                PeerConnection.IceConnectionState.CLOSED -> stopSelf()
                else -> {}
            }
        }
        override fun onSignalingChange(s: PeerConnection.SignalingState) {}
        override fun onIceConnectionReceivingChange(b: Boolean) {}
        override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
        override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
        override fun onAddStream(s: MediaStream) {}
        override fun onRemoveStream(s: MediaStream) {}
        override fun onDataChannel(d: org.webrtc.DataChannel) {}
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
        override fun onTrack(t: RtpTransceiver) {}
    }

    private open class Sdp : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) {}
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String?) {}
        override fun onSetFailure(e: String?) { if (e != null) Diario.anotar("SDP: $e") }
    }

    private val estadistica = object : Runnable {
        override fun run() {
            val pc = conexion
            if (pc != null && pc.iceConnectionState() in setOf(PeerConnection.IceConnectionState.CONNECTED, PeerConnection.IceConnectionState.COMPLETED)) {
                pc.getStats { informe ->
                    var bytes = 0L; var fotos = 0L; var camino = ""
                    for (s in informe.statsMap.values) {
                        if (s.type == "outbound-rtp") {
                            bytes += (s.members["bytesSent"] as? java.math.BigInteger)?.toLong() ?: 0L
                            fotos += (s.members["framesEncoded"] as? Long) ?: 0L
                        }
                        if (s.type == "local-candidate" && s.members["candidateType"] == "relay") camino = " · por reenvío"
                    }
                    val kbps = (bytes - ultimosBytes) * 8 / 1000
                    estado = "Emitiendo al coche $coche$camino\n$ancho×$alto · ${fotos - ultimosFotos} fps · $kbps kbps"
                    ultimosBytes = bytes; ultimosFotos = fotos
                }
            }
            manejador.postDelayed(this, 1000)
        }
    }

    private fun notificacion(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("emision", "Emisión al coche", NotificationManager.IMPORTANCE_LOW))
        val parar = PendingIntent.getService(
            this, 1, Intent(this, CaptureService::class.java).setAction(ACCION_PARAR), PendingIntent.FLAG_IMMUTABLE,
        )
        val abrir = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "emision")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Emitiendo la pantalla al coche")
            .setContentText("Toca para ver el estado")
            .setContentIntent(abrir)
            .addAction(Notification.Action.Builder(null, "Parar", parar).build())
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        activo = false
        Diario.anotar("Emisión terminada")
        if (!estado.startsWith("Error")) estado = "Parado"
        if (::manejador.isInitialized) {
            manejador.removeCallbacksAndMessages(null)
            manejador.post {
                try { senal?.cerrar() } catch (_: Exception) {}
                try { conexion?.dispose() } catch (_: Exception) {}
                try { capturador?.stopCapture() } catch (_: Exception) {}
                try { capturador?.dispose() } catch (_: Exception) {}
                try { pista?.dispose(); fuente?.dispose(); ayudante?.dispose() } catch (_: Exception) {}
                try { fabrica?.dispose(); egl?.release() } catch (_: Exception) {}
                hilo.quitSafely()
            }
        }
        bloqueo?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
