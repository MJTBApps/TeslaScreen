package es.mjtbapps.espejocoche

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de lo que pasa en la app, guardado en un archivo para que se vea en
 * pantalla aunque la app se haya cerrado por un fallo (versión de pruebas).
 */
object Diario {
    private const val MAX_LINEAS = 40
    private var archivo: File? = null
    private val hora = SimpleDateFormat("HH:mm:ss", Locale.ROOT)

    fun iniciar(ctx: Context) {
        archivo = File(ctx.filesDir, "diario.txt")
    }

    @Synchronized
    fun anotar(texto: String) {
        Log.i("EspejoCoche", texto)
        val f = archivo ?: return
        try {
            val lineas = (if (f.exists()) f.readLines() else emptyList()) + "${hora.format(Date())}  $texto"
            f.writeText(lineas.takeLast(MAX_LINEAS).joinToString("\n"))
        } catch (_: Exception) {}
    }

    @Synchronized
    fun texto(): String = try {
        archivo?.takeIf { it.exists() }?.readLines()?.reversed()?.joinToString("\n") ?: ""
    } catch (_: Exception) { "" }

    fun error(donde: String, e: Throwable) {
        val pila = e.stackTrace.take(4).joinToString(" ← ") { "${it.fileName}:${it.lineNumber}" }
        anotar("ERROR $donde: ${e.javaClass.simpleName}: ${e.message} [$pila]")
    }

    val movil: String
        get() = "${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"
}

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Diario.iniciar(this)
        val anterior = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { hilo, e ->
            Diario.error("CIERRE en el hilo «${hilo.name}»", e)
            anterior?.uncaughtException(hilo, e)
        }
    }
}
