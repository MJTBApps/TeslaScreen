package es.mjtbapps.espejocoche

import android.os.Handler
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Señalización con el servidor público de PeerJS (el mismo que usa rtc-ver.html en el coche).
 * Solo pasa los mensajes para presentar el móvil al coche (oferta, respuesta y candidatos);
 * el vídeo va después directamente o por el servidor de reenvío (TURN).
 *
 * Protocolo de PeerJS 1.5: mensajes JSON {type, payload, dst/src}.
 */
class Senal(
    private val alAbrir: () -> Unit,
    private val alMensaje: (tipo: String, payload: JSONObject) -> Unit,
    private val alFallar: (String) -> Unit,
) {
    val miId = "espejomovil-" + UUID.randomUUID().toString().take(12)
    private val cliente = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
    private var ws: WebSocket? = null
    private val latido = Handler(Looper.getMainLooper())
    @Volatile private var cerrado = false

    fun conectar() {
        val token = UUID.randomUUID().toString().take(10)
        val url = "wss://0.peerjs.com/peerjs?key=peerjs&id=$miId&token=$token&version=1.5.4"
        ws = cliente.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = try { JSONObject(text) } catch (_: Exception) { return }
                when (val tipo = m.optString("type")) {
                    "OPEN" -> { latir(); alAbrir() }
                    "ERROR", "ID-TAKEN", "INVALID-KEY" -> alFallar("servidor: $tipo ${m.optJSONObject("payload")?.optString("msg") ?: ""}")
                    "EXPIRE" -> alFallar("el coche no está esperando con ese código")
                    "LEAVE" -> alFallar("el coche ha cerrado la página")
                    else -> alMensaje(tipo, m.optJSONObject("payload") ?: JSONObject())
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (!cerrado) alFallar("sin conexión con el servidor de emparejamiento: ${t.message}")
            }
        })
    }

    private fun latir() {
        if (cerrado) return
        ws?.send("""{"type":"HEARTBEAT"}""")
        latido.postDelayed({ latir() }, 5000)
    }

    fun enviar(tipo: String, destino: String, payload: JSONObject) {
        ws?.send(JSONObject().put("type", tipo).put("dst", destino).put("payload", payload).toString())
    }

    fun cerrar() {
        cerrado = true
        latido.removeCallbacksAndMessages(null)
        ws?.close(1000, null)
        cliente.dispatcher.executorService.shutdown()
    }
}
