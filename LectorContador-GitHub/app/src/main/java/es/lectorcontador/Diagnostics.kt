package es.lectorcontador

import android.os.SystemClock
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Prueba el puerto óptico con varias velocidades y formatos serie y registra lo que llega,
 * para averiguar qué protocolo habla un contador desconocido.
 *
 * Por cada combinación: escucha en pasivo, envía la petición IEC 62056-21 ("/?!\r\n")
 * y vuelve a escuchar. Solo envía esa petición de lectura; no escribe nada en el contador.
 */
class MeterDiagnostics(
    private val port: UsbSerialPort,
    private val log: (String) -> Unit,
) {
    private class Format(val label: String, val dataBits: Int, val parity: Int)

    suspend fun run() = withContext(Dispatchers.IO) {
        val dev = port.driver.device
        log("Sonda USB: VID 0x%04X, PID 0x%04X".format(dev.vendorId, dev.productId))
        log("Se prueba cada combinación: escucha 1,5 s, envía «/?!» y escucha de nuevo.")
        log("")

        val formats = listOf(
            Format("7E1", 7, UsbSerialPort.PARITY_EVEN),
            Format("8N1", 8, UsbSerialPort.PARITY_NONE),
        )
        val bauds = listOf(300, 1200, 2400, 4800, 9600)

        var gotAnything = false
        var identified: Identification? = null

        for (baud in bauds) {
            for (f in formats) {
                coroutineContext.ensureActive()
                port.setParameters(baud, f.dataBits, UsbSerialPort.STOPBITS_1, f.parity)
                runCatching { port.purgeHwBuffers(true, true) }

                val passive = listen(1500)
                port.write(Iec62056.REQUEST, 1000)
                val response = listen(if (baud == 300) 3500 else 2000)

                val head = "$baud ${f.label}:"
                if (passive.isNotEmpty()) {
                    gotAnything = true
                    log("$head el contador envía datos sin pedirlos (${passive.size} bytes)")
                    log("   ASCII: ${ByteFormat.ascii(passive)}")
                    log("   HEX:   ${ByteFormat.hex(passive)}")
                }
                when {
                    response.isEmpty() -> if (passive.isEmpty()) log("$head sin respuesta")
                    isEcho(response) -> log("$head solo eco de la petición (la sonda devuelve lo que envía)")
                    else -> {
                        gotAnything = true
                        log("$head RESPUESTA (${response.size} bytes)")
                        log("   ASCII: ${ByteFormat.ascii(response)}")
                        log("   HEX:   ${ByteFormat.hex(response)}")
                        val text = String(
                            ByteArray(response.size) { (response[it].toInt() and 0x7F).toByte() },
                            Charsets.US_ASCII,
                        )
                        Iec62056.findIdentification(text)?.let { identified = it }
                    }
                }
                delay(200)
            }
        }

        log("")
        val id = identified
        when {
            id != null -> log(
                "RESULTADO: el contador habla IEC 62056-21 (${id.manufacturer} ${id.text}). " +
                    "La lectura normal debería funcionar."
            )
            gotAnything -> log(
                "RESULTADO: el contador emite datos, pero no es el saludo IEC 62056-21. " +
                    "Comparte este informe para identificar el protocolo."
            )
            else -> log(
                "RESULTADO: ninguna respuesta. Comprueba la alineación de la sonda con el puerto óptico; " +
                    "si está bien, ese puerto no responde a este protocolo."
            )
        }
    }

    /** Eco típico de la petición, con la paridad enmascarada. */
    private fun isEcho(bytes: ByteArray): Boolean {
        val masked = ByteArray(bytes.size) { (bytes[it].toInt() and 0x7F).toByte() }
        return masked.contentEquals(Iec62056.REQUEST)
    }

    private suspend fun listen(ms: Long): ByteArray {
        val acc = ByteArrayOutputStream()
        val buf = ByteArray(512)
        val deadline = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < deadline) {
            coroutineContext.ensureActive()
            val n = port.read(buf, 200)
            if (n > 0) acc.write(buf, 0, n)
        }
        return acc.toByteArray()
    }
}
