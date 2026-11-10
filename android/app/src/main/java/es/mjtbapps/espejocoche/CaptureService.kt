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
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.view.Display
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Captura la pantalla (MediaProjection), la comprime en H.264 por hardware
 * (MediaCodec) y la sirve por la red local con [VideoServer].
 *
 * Android 14+: la proyección solo admite una pantalla virtual, así que al girar
 * el móvil se reutiliza la misma (resize + setSurface) con un codificador nuevo.
 */
class CaptureService : Service() {

    companion object {
        const val EXTRA_CODIGO = "codigo"
        const val EXTRA_DATOS = "datos"
        const val ACCION_PARAR = "es.mjtbapps.espejocoche.PARAR"
        const val PUERTO = 8765
        private const val LADO_MAYOR = 1280
        private const val TAG = "EspejoCoche"

        @Volatile var activo = false
        @Volatile var estado = "Parado"
    }

    private lateinit var hilo: HandlerThread
    private lateinit var manejador: Handler
    private var proyeccion: MediaProjection? = null
    private var pantalla: VirtualDisplay? = null
    private var codificador: MediaCodec? = null
    private var vaciado: Thread? = null
    @Volatile private var ancho = 0
    @Volatile private var alto = 0
    private var servidor: VideoServer? = null
    private var bloqueo: PowerManager.WakeLock? = null
    private var ultimoBytes = 0L
    private var ultimosPaquetes = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACCION_PARAR) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (activo) return START_NOT_STICKY
        // Android 14+: primero el servicio en primer plano, luego la proyección.
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, notificacion(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(1, notificacion())
        }
        val codigo = intent?.getIntExtra(EXTRA_CODIGO, Activity.RESULT_CANCELED) ?: Activity.RESULT_CANCELED
        @Suppress("DEPRECATION")
        val datos = intent?.getParcelableExtra<Intent>(EXTRA_DATOS)
        if (codigo != Activity.RESULT_OK || datos == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        activo = true
        estado = "Arrancando…"
        hilo = HandlerThread("captura").also { it.start() }
        manejador = Handler(hilo.looper)
        bloqueo = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "EspejoCoche:emision").also { it.acquire(4 * 60 * 60 * 1000L) }
        manejador.post { arrancar(codigo, datos) }
        return START_NOT_STICKY
    }

    private fun arrancar(codigo: Int, datos: Intent) {
        try {
            val mpm = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val p = mpm.getMediaProjection(codigo, datos) ?: throw IllegalStateException("sin proyección")
            proyeccion = p
            p.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopSelf() }
                override fun onCapturedContentResize(width: Int, height: Int) {
                    manejador.post { reconfigurar(width, height) }
                }
            }, manejador)
            servidor = VideoServer(PUERTO, ::pedirClave) { ancho to alto }.also { it.arrancar() }
            val (w, h) = tamanoPantalla()
            val (aw, ah) = escalar(w, h)
            val superficie = crearCodificador(aw, ah)
            pantalla = p.createVirtualDisplay(
                "EspejoCoche", aw, ah, resources.displayMetrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, superficie, null, manejador,
            )
            (getSystemService(Context.DISPLAY_SERVICE) as DisplayManager)
                .registerDisplayListener(object : DisplayManager.DisplayListener {
                    override fun onDisplayAdded(id: Int) {}
                    override fun onDisplayRemoved(id: Int) {}
                    override fun onDisplayChanged(id: Int) {
                        if (id == Display.DEFAULT_DISPLAY) {
                            val (nw, nh) = tamanoPantalla()
                            reconfigurar(nw, nh)
                        }
                    }
                }, manejador)
            manejador.post(estadistica)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo arrancar", e)
            estado = "Error: ${e.message}"
            stopSelf()
        }
    }

    private fun tamanoPantalla(): Pair<Int, Int> {
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        val r = wm.maximumWindowMetrics.bounds
        return r.width() to r.height()
    }

    /** Lado mayor a 1280 y múltiplos de 16 (lo piden muchos codificadores). */
    private fun escalar(w: Int, h: Int): Pair<Int, Int> {
        val f = minOf(1.0, LADO_MAYOR.toDouble() / max(w, h))
        fun r16(x: Double) = max(16, (x / 16).roundToInt() * 16)
        return r16(w * f) to r16(h * f)
    }

    private fun reconfigurar(w: Int, h: Int) {
        val (aw, ah) = escalar(w, h)
        if (aw == ancho && ah == alto) return
        val vd = pantalla ?: return
        Log.i(TAG, "Nuevo tamaño $aw×$ah")
        vd.surface = null
        pararCodificador()
        val superficie = crearCodificador(aw, ah)
        vd.resize(aw, ah, resources.displayMetrics.densityDpi)
        vd.surface = superficie
    }

    private fun crearCodificador(w: Int, h: Int): android.view.Surface {
        fun formato(conPerfil: Boolean) = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2)
            // Si la pantalla no cambia, se repite el último fotograma (el coche sabe que seguimos vivos).
            setLong(MediaFormat.KEY_REPEAT_PREVIOUS_FRAME_AFTER, 100_000)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            if (Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            if (conPerfil) {
                setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline)
                val macrobloques = (w / 16) * (h / 16)
                setInteger(
                    MediaFormat.KEY_LEVEL,
                    if (macrobloques <= 3600) MediaCodecInfo.CodecProfileLevel.AVCLevel31 else MediaCodecInfo.CodecProfileLevel.AVCLevel4,
                )
            }
        }
        var c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            c.configure(formato(true), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        } catch (e: Exception) {
            Log.w(TAG, "Sin perfil Baseline, se usa el del codificador", e)
            c.release()
            c = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(formato(false), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val superficie = c.createInputSurface()
        c.start()
        codificador = c
        ancho = w
        alto = h
        vaciado = Thread({ vaciar(c) }, "codificador").also { it.start() }
        return superficie
    }

    private fun vaciar(c: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        var configuracion: ByteArray? = null
        try {
            while (codificador === c) {
                val i = c.dequeueOutputBuffer(info, 20_000)
                if (i < 0) continue
                val buf = c.getOutputBuffer(i)
                if (buf != null && info.size > 0) {
                    val datos = ByteArray(info.size)
                    buf.position(info.offset)
                    buf.get(datos)
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) {
                        configuracion = datos // SPS y PPS
                    } else {
                        val clave = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
                        val cfg = configuracion
                        // Cada fotograma clave lleva delante SPS y PPS: el coche puede empezar en cualquiera.
                        val paquete = if (clave && cfg != null && !empiezaConSps(datos)) cfg + datos else datos
                        servidor?.difundir(clave, info.presentationTimeUs, paquete)
                    }
                }
                c.releaseOutputBuffer(i, false)
            }
        } catch (e: IllegalStateException) {
            // codificador parado mientras se leía
        }
    }

    private fun empiezaConSps(d: ByteArray): Boolean {
        val i = if (d.size > 4 && d[2].toInt() == 1) 3 else 4
        return d.size > i && (d[i].toInt() and 0x1f) == 7
    }

    private fun pedirClave() {
        try {
            codificador?.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) })
        } catch (_: Exception) {}
    }

    private fun pararCodificador() {
        val c = codificador ?: return
        codificador = null
        vaciado?.join(500)
        try { c.stop() } catch (_: Exception) {}
        c.release()
    }

    private val estadistica = object : Runnable {
        override fun run() {
            val s = servidor ?: return
            val b = s.bytesEnviados.get()
            val p = s.paquetesEnviados.get()
            val mbps = (b - ultimoBytes) * 8 / 1e6
            val fps = p - ultimosPaquetes
            ultimoBytes = b
            ultimosPaquetes = p
            estado = if (s.numClientes == 0) {
                "Emitiendo $ancho×$alto · esperando al coche"
            } else {
                "Conectado: ${s.ultimoCliente} → ${s.ultimaDireccionLocal}\n$ancho×$alto · $fps fps · ${"%.1f".format(mbps)} Mbps · ${s.numClientes} pantalla(s)"
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
            .setContentText("Toca para ver la dirección")
            .setContentIntent(abrir)
            .addAction(Notification.Action.Builder(null, "Parar", parar).build())
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        activo = false
        estado = "Parado"
        if (::manejador.isInitialized) {
            manejador.removeCallbacksAndMessages(null)
            manejador.post {
                try { pantalla?.release() } catch (_: Exception) {}
                pararCodificador()
                servidor?.parar()
                try { proyeccion?.stop() } catch (_: Exception) {}
                hilo.quitSafely()
            }
        }
        bloqueo?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }
}
