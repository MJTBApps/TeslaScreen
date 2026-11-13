package es.mjtbapps.espejocoche

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Path
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import org.json.JSONObject
import kotlin.math.hypot

/**
 * Control táctil desde la pantalla del coche. Android solo deja tocar la pantalla
 * a otra app mediante un servicio de accesibilidad (como TeamViewer o AnyDesk),
 * que el usuario activa a mano en Ajustes.
 *
 * Mensajes del coche (JSON, posiciones de 0 a 1):
 * {t:"d"|"m"|"u", x, y}: dedo abajo, moviéndose, arriba · {t:"atras"|"inicio"|"recientes"}.
 * Al levantar el dedo se reproduce el gesto: toque, pulsación larga o arrastre con su recorrido.
 */
class Tactil : AccessibilityService() {

    companion object {
        @Volatile var instancia: Tactil? = null
            private set

        fun activo(ctx: Context): Boolean {
            val lista = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
            return lista.split(':').any { it.startsWith(ctx.packageName + "/") }
        }
    }

    private val puntos = ArrayList<FloatArray>()  // x, y en píxeles
    private var inicio = 0L

    override fun onServiceConnected() { instancia = this; Diario.anotar("Control táctil listo") }
    override fun onDestroy() { if (instancia === this) instancia = null; super.onDestroy() }
    override fun onUnbind(intent: android.content.Intent?): Boolean { if (instancia === this) instancia = null; return super.onUnbind(intent) }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    private fun pixeles(x: Double, y: Double): FloatArray {
        val b = (getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager).maximumWindowMetrics.bounds
        return floatArrayOf((x * (b.width() - 1)).toFloat(), (y * (b.height() - 1)).toFloat())
    }

    fun mensaje(m: JSONObject) {
        when (m.optString("t")) {
            "atras" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "inicio" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "recientes" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "d" -> { puntos.clear(); puntos += pixeles(m.optDouble("x"), m.optDouble("y")); inicio = SystemClock.uptimeMillis() }
            "m" -> if (puntos.isNotEmpty() && puntos.size < 500) puntos += pixeles(m.optDouble("x"), m.optDouble("y"))
            "u" -> if (puntos.isNotEmpty()) {
                puntos += pixeles(m.optDouble("x"), m.optDouble("y"))
                gesto(SystemClock.uptimeMillis() - inicio)
                puntos.clear()
            }
        }
    }

    private fun gesto(duracionDedo: Long) {
        val a = puntos.first()
        val recorrido = puntos.zipWithNext { p, q -> hypot(q[0] - p[0], q[1] - p[1]) }.sum()
        val camino = Path().apply { moveTo(a[0], a[1]) }
        val ms: Long
        if (recorrido < 24f) {
            // Toque o pulsación larga (más de medio segundo sin moverse).
            ms = if (duracionDedo > 500) 700 else 60
        } else {
            for (p in puntos.drop(1)) camino.lineTo(p[0], p[1])
            // Mismo tiempo que en el coche, dentro de lo que admite Android.
            ms = duracionDedo.coerceIn(80, 3000)
        }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(camino, 0, ms)).build()
        dispatchGesture(g, null, null)
    }
}
