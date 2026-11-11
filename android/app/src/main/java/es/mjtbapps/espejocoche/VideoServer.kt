package es.mjtbapps.espejocoche

import android.util.Base64
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Servidor mínimo para el navegador del coche (mismo protocolo que
 * herramientas/emisor_prueba.py):
 *
 * - GET /ping   → {"emisor":…, "relojUs":…} (reloj del móvil, para medir el retraso)
 * - GET /flujo  → vídeo por fetch (HTTP con Transfer-Encoding: chunked)
 * - GET /ws     → vídeo por WebSocket
 *
 * Cada paquete: 4 bytes de longitud (big endian, cuenta lo que sigue) + 1 byte
 * (1 = fotograma clave) + 8 bytes de marca de tiempo en µs + H.264 Annex B.
 */
class VideoServer(
    private val puerto: Int,
    private val pedirClave: () -> Unit,
    private val tamano: () -> Pair<Int, Int>,
) {
    private class Cliente(val socket: Socket) {
        val cola = ArrayBlockingQueue<ByteArray>(45)
        @Volatile var necesitaClave = true
    }

    private val clientes = CopyOnWriteArrayList<Cliente>()
    private var servidor: ServerSocket? = null
    val bytesEnviados = AtomicLong()
    val paquetesEnviados = AtomicLong()
    @Volatile var ultimaDireccionLocal: String? = null
    @Volatile var ultimoCliente: String? = null

    val numClientes get() = clientes.size

    fun arrancar() {
        val s = ServerSocket()
        s.reuseAddress = true
        s.bind(InetSocketAddress(puerto))
        servidor = s
        Thread({
            while (!s.isClosed) {
                val socket = try { s.accept() } catch (e: Exception) { break }
                Thread({ atender(socket) }, "cliente").start()
            }
        }, "servidor").start()
    }

    fun parar() {
        try { servidor?.close() } catch (_: Exception) {}
        clientes.forEach { try { it.socket.close() } catch (_: Exception) {} }
        clientes.clear()
    }

    /** Lo llama el codificador con cada fotograma. */
    fun difundir(clave: Boolean, tiempoUs: Long, datos: ByteArray) {
        if (clientes.isEmpty()) return
        val paquete = ByteBuffer.allocate(13 + datos.size)
            .putInt(9 + datos.size).put(if (clave) 1 else 0).putLong(tiempoUs).put(datos).array()
        for (c in clientes) {
            if (c.necesitaClave && !clave) continue
            c.necesitaClave = false
            if (!c.cola.offer(paquete)) {
                // El navegador no da abasto: se tira lo pendiente y se espera al siguiente fotograma clave.
                c.cola.clear()
                c.necesitaClave = true
                pedirClave()
            }
        }
    }

    private fun atender(socket: Socket) {
        try {
            socket.tcpNoDelay = true
            socket.soTimeout = 10_000
            val entrada = socket.getInputStream()
            val cabeceras = leerCabeceras(entrada) ?: return
            val primera = cabeceras.first().split(" ")
            val metodo = primera.getOrNull(0) ?: ""
            val ruta = (primera.getOrNull(1) ?: "/").substringBefore("?")
            val h = cabeceras.drop(1).mapNotNull {
                val i = it.indexOf(':')
                if (i > 0) it.substring(0, i).trim().lowercase() to it.substring(i + 1).trim() else null
            }.toMap()
            ultimaDireccionLocal = socket.localAddress.hostAddress
            ultimoCliente = socket.inetAddress.hostAddress
            val salida = BufferedOutputStream(socket.getOutputStream(), 64 * 1024)
            when {
                metodo == "OPTIONS" -> responder(salida, "204 No Content", "text/plain", ByteArray(0))
                ruta == "/ping" -> {
                    val (w, hh) = tamano()
                    val json = """{"emisor":"EspejoCoche","relojUs":${System.nanoTime() / 1000},"ancho":$w,"alto":$hh}"""
                    responder(salida, "200 OK", "application/json", json.toByteArray())
                }
                ruta == "/flujo" -> {
                    Diario.anotar("El coche (${socket.inetAddress.hostAddress}) pide el vídeo por fetch")
                    flujo(socket, salida)
                }
                ruta == "/ws" -> {
                    Diario.anotar("El coche (${socket.inetAddress.hostAddress}) pide el vídeo por WebSocket")
                    websocket(socket, salida, h["sec-websocket-key"])
                }
                else -> {
                    val texto = "EspejoCoche: abre https://mjtbapps.github.io/TeslaScreen/ver.html?ip=${socket.localAddress.hostAddress} en el navegador del coche."
                    responder(salida, "200 OK", "text/plain; charset=utf-8", texto.toByteArray())
                }
            }
        } catch (_: Exception) {
        } finally {
            try { socket.close() } catch (_: Exception) {}
        }
    }

    private fun cors(): String =
        "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Private-Network: true\r\n" +
            "Access-Control-Allow-Local-Network: true\r\n" +
            "Access-Control-Allow-Headers: *\r\n" +
            "Cache-Control: no-store\r\n"

    private fun responder(salida: OutputStream, estado: String, tipo: String, cuerpo: ByteArray) {
        salida.write(
            ("HTTP/1.1 $estado\r\n" + cors() + "Content-Type: $tipo\r\nContent-Length: ${cuerpo.size}\r\nConnection: close\r\n\r\n").toByteArray()
        )
        salida.write(cuerpo)
        salida.flush()
    }

    private fun flujo(socket: Socket, salida: OutputStream) {
        salida.write(
            ("HTTP/1.1 200 OK\r\n" + cors() + "Content-Type: application/octet-stream\r\nTransfer-Encoding: chunked\r\n\r\n").toByteArray()
        )
        salida.flush()
        emitir(socket) { p ->
            salida.write((Integer.toHexString(p.size) + "\r\n").toByteArray())
            salida.write(p)
            salida.write("\r\n".toByteArray())
            salida.flush()
        }
    }

    private fun websocket(socket: Socket, salida: OutputStream, clave: String?) {
        if (clave == null) {
            responder(salida, "400 Bad Request", "text/plain", "Falta Sec-WebSocket-Key".toByteArray())
            return
        }
        val sha = MessageDigest.getInstance("SHA-1").digest((clave + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray())
        val acepta = Base64.encodeToString(sha, Base64.NO_WRAP)
        salida.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $acepta\r\n\r\n".toByteArray())
        salida.flush()
        emitir(socket) { p ->
            val n = p.size
            when {
                n < 126 -> salida.write(byteArrayOf(0x82.toByte(), n.toByte()))
                n < 65536 -> salida.write(byteArrayOf(0x82.toByte(), 126, (n shr 8).toByte(), n.toByte()))
                else -> salida.write(ByteBuffer.allocate(10).put(0x82.toByte()).put(127).putLong(n.toLong()).array())
            }
            salida.write(p)
            salida.flush()
        }
    }

    private fun emitir(socket: Socket, escribir: (ByteArray) -> Unit) {
        val c = Cliente(socket)
        clientes.add(c)
        pedirClave()
        try {
            while (!socket.isClosed) {
                val p = c.cola.poll(2, TimeUnit.SECONDS) ?: continue
                escribir(p)
                bytesEnviados.addAndGet(p.size.toLong())
                paquetesEnviados.incrementAndGet()
            }
        } finally {
            clientes.remove(c)
        }
    }

    private fun leerCabeceras(entrada: InputStream): List<String>? {
        val b = StringBuilder()
        while (b.length < 16 * 1024) {
            val c = entrada.read()
            if (c < 0) return null
            b.append(c.toChar())
            if (b.endsWith("\r\n\r\n")) return b.trim().split("\r\n")
        }
        return null
    }
}
