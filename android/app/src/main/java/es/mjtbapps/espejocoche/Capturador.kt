package es.mjtbapps.espejocoche

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.projection.MediaProjection
import android.view.Surface
import org.webrtc.CapturerObserver
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoCapturer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink

/**
 * Captura de pantalla para WebRTC con una sola pantalla virtual: al girar el móvil
 * se cambia su tamaño (resize) en lugar de crear otra, porque desde Android 14 una
 * proyección solo admite una (el ScreenCapturerAndroid de WebRTC crea otra y falla,
 * y sin girar el vídeo horizontal se ve diminuto dentro de un marco vertical).
 */
class Capturador(private val proyeccion: MediaProjection, private val dpi: Int) : VideoCapturer, VideoSink {
    private lateinit var ayudante: SurfaceTextureHelper
    private lateinit var observador: CapturerObserver
    private var pantalla: VirtualDisplay? = null
    @Volatile var ancho = 0; private set
    @Volatile var alto = 0; private set

    override fun initialize(h: SurfaceTextureHelper, ctx: Context, o: CapturerObserver) {
        ayudante = h; observador = o
    }

    override fun startCapture(w: Int, h: Int, fps: Int) {
        ancho = w; alto = h
        ayudante.handler.post {
            ayudante.setTextureSize(w, h)
            pantalla = proyeccion.createVirtualDisplay(
                "EspejoCoche", w, h, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                Surface(ayudante.surfaceTexture), null, null,
            )
            observador.onCapturerStarted(true)
            ayudante.startListening(this)
        }
    }

    /** Giro del móvil: misma pantalla virtual con el nuevo tamaño. */
    override fun changeCaptureFormat(w: Int, h: Int, fps: Int) {
        if (w == ancho && h == alto) return
        ancho = w; alto = h
        ayudante.handler.post {
            ayudante.setTextureSize(w, h)
            pantalla?.resize(w, h, dpi)
        }
    }

    override fun onFrame(f: VideoFrame) = observador.onFrameCaptured(f)

    override fun stopCapture() {
        ayudante.handler.post {
            ayudante.stopListening()
            observador.onCapturerStopped()
            pantalla?.release(); pantalla = null
        }
    }

    override fun dispose() {}
    override fun isScreencast() = true
}
