package es.mjtbapps.espejocoche

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Pantalla de prueba: código del coche, datos del reenvío, empezar y estado. */
class MainActivity : Activity() {

    private lateinit var codigo: EditText
    private lateinit var turnU: EditText
    private lateinit var turnP: EditText
    private lateinit var estado: TextView
    private lateinit var boton: Button
    private lateinit var diario: TextView
    private val reloj = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val dp = resources.displayMetrics.density
        val gris = Color.rgb(154, 166, 178)
        fun texto(tam: Float, negrita: Boolean = false) = TextView(this).apply {
            textSize = tam
            setTextColor(Color.WHITE)
            if (negrita) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt())
        }
        fun campo(pista: String, tipo: Int) = EditText(this).apply {
            hint = pista; inputType = tipo; setTextColor(Color.WHITE); setHintTextColor(gris); isSingleLine = true
        }
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(20, 24, 29))
            val m = (20 * dp).toInt()
            setPadding(m, m, m, m)
        }
        raiz.addView(texto(26f, true).apply { text = "EspejoCoche · prueba" })
        raiz.addView(texto(16f).apply {
            setTextColor(gris)
            text = "1. Conecta el coche a Internet (por ejemplo, al punto de acceso del móvil).\n" +
                "2. En el navegador del coche abre mjtbapps.github.io/TeslaScreen/rtc-ver.html\n" +
                "3. Escribe aquí el código que sale en el coche y pulsa «Empezar»."
        })
        codigo = campo("Código del coche (6 cifras)", InputType.TYPE_CLASS_NUMBER).apply {
            textSize = 28f; gravity = Gravity.CENTER; setText(Ajustes.coche(this@MainActivity))
        }
        raiz.addView(codigo)
        boton = Button(this).apply {
            textSize = 20f
            setOnClickListener { if (CaptureService.activo) parar() else empezar() }
        }
        raiz.addView(boton, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        estado = texto(16f)
        raiz.addView(estado)

        // Datos del servidor de reenvío (ExpressTURN): se guardan solo en el móvil.
        val (u, p) = Ajustes.turn(this)
        val caja = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = if (u.isEmpty()) View.VISIBLE else View.GONE }
        raiz.addView(texto(16f, true).apply {
            text = "Servidor de reenvío (TURN) ▾"
            setOnClickListener { caja.visibility = if (caja.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        })
        caja.addView(texto(14f).apply { setTextColor(gris); text = "Usuario y contraseña de tu panel de ExpressTURN." })
        turnU = campo("Usuario", InputType.TYPE_CLASS_TEXT).apply { setText(u) }
        turnP = campo("Contraseña", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD).apply { setText(p) }
        caja.addView(turnU); caja.addView(turnP)
        raiz.addView(caja)

        raiz.addView(texto(14f).apply {
            setTextColor(gris)
            text = "El móvil tiene que seguir desbloqueado: con la pantalla bloqueada Android no deja capturarla."
        })
        raiz.addView(texto(13f).apply {
            setTextColor(gris)
            text = Diario.movil + " · app " + packageManager.getPackageInfo(packageName, 0).versionName
        })
        raiz.addView(texto(16f, true).apply { text = "Registro (si falla, haz una foto)" })
        diario = texto(12f).apply { typeface = Typeface.MONOSPACE; setTextIsSelectable(true) }
        raiz.addView(diario)
        val scroll = ScrollView(this).apply { addView(raiz, MATCH_PARENT, WRAP_CONTENT); setBackgroundColor(Color.rgb(20, 24, 29)) }
        // Desde Android 15 la app se dibuja bajo las barras del sistema.
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(scroll)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        }
        if (!Ajustes.avisoAceptado(this)) aviso()
    }

    private fun aviso() {
        AlertDialog.Builder(this)
            .setTitle("Antes de empezar")
            .setMessage(
                "EspejoCoche muestra la pantalla del móvil en el navegador del coche para usar mapas, música y otras apps.\n\n" +
                    "Úsala de forma segura y respetando las normas de tráfico. Lo que se haga con la app mientras se conduce " +
                    "es responsabilidad de quien conduce."
            )
            .setCancelable(false)
            .setPositiveButton("Entendido") { _, _ -> Ajustes.aceptarAviso(this) }
            .show()
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
            boton.text = if (CaptureService.activo) "Parar" else "Empezar"
            estado.text = CaptureService.estado
            diario.text = Diario.texto()
            reloj.postDelayed(this, 1000)
        }
    }

    private fun empezar() {
        val c = codigo.text.toString().trim()
        if (!Regex("^\\d{6}$").matches(c)) { estado.text = "Escribe el código de 6 cifras que sale en el coche."; return }
        Ajustes.guardarCoche(this, c)
        Ajustes.guardarTurn(this, turnU.text.toString().trim(), turnP.text.toString().trim())
        Diario.anotar("—— Empezar, coche $c (" + Diario.movil + ") ——")
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
            Diario.anotar("Permiso de grabación rechazado")
            estado.text = "Sin permiso para grabar la pantalla."
            return
        }
        try {
            startForegroundService(
                Intent(this, CaptureService::class.java)
                    .putExtra(CaptureService.EXTRA_CODIGO, resultCode)
                    .putExtra(CaptureService.EXTRA_DATOS, data)
                    .putExtra(CaptureService.EXTRA_COCHE, Ajustes.coche(this)),
            )
        } catch (e: Exception) {
            Diario.error("al iniciar el servicio", e)
        }
    }
}
