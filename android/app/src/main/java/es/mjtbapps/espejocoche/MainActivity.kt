package es.mjtbapps.espejocoche

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.net.Inet4Address
import java.net.NetworkInterface

/** Pantalla de prueba: dirección para el coche, botón de empezar y estado. */
class MainActivity : Activity() {

    private lateinit var direcciones: TextView
    private lateinit var estado: TextView
    private lateinit var boton: Button
    private val reloj = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dp = resources.displayMetrics.density
        fun texto(tam: Float, negrita: Boolean = false) = TextView(this).apply {
            textSize = tam
            setTextColor(Color.WHITE)
            if (negrita) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        }
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(20, 24, 29))
            val m = (20 * dp).toInt()
            setPadding(m, m, m, m)
        }
        raiz.addView(texto(26f, true).apply { text = "EspejoCoche · prueba" })
        raiz.addView(texto(16f).apply {
            setTextColor(Color.rgb(154, 166, 178))
            text = "1. Activa el punto de acceso del móvil y conecta el coche a él.\n" +
                "2. Pulsa «Empezar» y acepta grabar la pantalla (mejor «pantalla completa»).\n" +
                "3. En el navegador del coche abre la dirección de abajo."
        })
        direcciones = texto(20f, true).apply { setTextColor(Color.rgb(240, 122, 46)); setTextIsSelectable(true) }
        raiz.addView(direcciones)
        boton = Button(this).apply {
            textSize = 20f
            setOnClickListener { if (CaptureService.activo) parar() else empezar() }
        }
        raiz.addView(boton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        estado = texto(16f)
        raiz.addView(estado)
        raiz.addView(texto(14f).apply {
            setTextColor(Color.rgb(154, 166, 178))
            text = "El móvil tiene que seguir desbloqueado: con la pantalla bloqueada Android no deja capturarla. " +
                "Manejar el móvil o la pantalla del coche circulando es responsabilidad de quien conduce."
        })
        val scroll = ScrollView(this).apply { addView(raiz, MATCH_PARENT, WRAP_CONTENT); setBackgroundColor(Color.rgb(20, 24, 29)) }
        // Desde Android 15 la app se dibuja bajo las barras del sistema.
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
    }

    override fun onResume() {
        super.onResume()
        reloj.post(refrescar)
    }

    override fun onPause() {
        reloj.removeCallbacks(refrescar)
        super.onPause()
    }

    private val refrescar = object : Runnable {
        override fun run() {
            direcciones.text = textoDirecciones()
            boton.text = if (CaptureService.activo) "Parar" else "Empezar"
            estado.text = CaptureService.estado
            reloj.postDelayed(this, 1000)
        }
    }

    private fun textoDirecciones(): String {
        val ips = try {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { ni -> ni.inetAddresses.toList().filterIsInstance<Inet4Address>().map { ni.name to it.hostAddress!! } }
        } catch (_: Exception) { emptyList() }
        if (ips.isEmpty()) return "Sin red: activa el punto de acceso."
        // El punto de acceso suele llamarse ap0, swlan0, wlan1… y la IP acaba en .1.
        val ordenadas = ips.sortedByDescending { (n, ip) ->
            (if (Regex("^(ap|swlan|softap|wlan1)").containsMatchIn(n)) 2 else 0) + (if (ip.endsWith(".1")) 1 else 0)
        }
        return ordenadas.joinToString("\n") { (n, ip) -> "mjtbapps.github.io/TeslaScreen/ver.html?ip=$ip   ($n)" }
    }

    private fun empezar() {
        val mpm = getSystemService(MediaProjectionManager::class.java)
        @Suppress("DEPRECATION")
        startActivityForResult(mpm.createScreenCaptureIntent(), 1)
    }

    private fun parar() {
        startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACCION_PARAR))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 1) return
        if (resultCode != RESULT_OK || data == null) {
            estado.text = "Sin permiso para grabar la pantalla."
            return
        }
        startForegroundService(
            Intent(this, CaptureService::class.java)
                .putExtra(CaptureService.EXTRA_CODIGO, resultCode)
                .putExtra(CaptureService.EXTRA_DATOS, data),
        )
    }
}
