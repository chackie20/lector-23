package es.lectorcontador

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Ejecuta una lectura completa IEC 62056-21 modo C a través de un puerto USB-serie. */
class MeterLink(
    private val port: UsbSerialPort,
    private val onStatus: (String) -> Unit = {},
) {
    suspend fun readOut(): MeterReading = withContext(Dispatchers.IO) {
        setBaud(Iec62056.INITIAL_BAUD)
        runCatching { port.purgeHwBuffers(true, true) }

        onStatus("Enviando petición al contador…")
        port.write(Iec62056.REQUEST, WRITE_TIMEOUT_MS)

        val identBytes = port.collect(IDENT_TIMEOUT_MS) {
            Iec62056.findIdentification(asciiOf(it)) != null
        }.takeIf { it.complete }?.bytes ?: throw IOException(
            "El contador no responde. Comprueba que la sonda está bien alineada con el puerto óptico."
        )
        val ident = Iec62056.findIdentification(asciiOf(identBytes))!!
        val newBaud = Iec62056.baudFor(ident.baudChar)!!
        val name = "${ident.manufacturer} ${ident.text}".trim()
        onStatus("Contador $name")

        // El estándar pide esperar un poco antes de contestar con el ACK.
        delay(300)
        port.write(Iec62056.ackReadout(ident.baudChar), WRITE_TIMEOUT_MS)
        // El ACK (6 bytes a 300 baud) tarda ~200 ms en salir antes de cambiar la velocidad.
        delay(350)
        setBaud(newBaud)

        onStatus("Leyendo datos a $newBaud baudios…")
        val data = port.collect(DATA_TIMEOUT_MS, SILENT_AFTER_ACK_MS) { Iec62056.extractFrame(it) != null }
        if (!data.complete) {
            if (data.bytes.isEmpty()) {
                // El contador identificó pero no envía la lectura: no implementa el modo C (lectura estándar).
                throw IOException(
                    "El contador ($name) se identificó, pero no envía datos en modo C (lectura estándar). " +
                        "Es lo habitual en los contadores inteligentes con DLMS/COSEM: en Ajustes elige el " +
                        "protocolo DLMS/COSEM (o toca la tarjeta de su foto) y vuelve a leer."
                )
            }
            throw IOException(
                "Llegaron ${data.bytes.size} bytes pero no forman una trama válida: ${ByteFormat.ascii(data.bytes)}"
            )
        }
        val frameBytes = data.bytes

        // Cierre de sesión (no es crítico si falla)
        runCatching { port.write(Iec62056.breakFrame(), WRITE_TIMEOUT_MS) }

        when (val frame = Iec62056.extractFrame(frameBytes)) {
            is FrameResult.Ok -> {
                val values = Iec62056.parseDataBlock(frame.payload)
                if (values.isEmpty()) throw IOException("El contador respondió, pero sin valores reconocibles.")
                MeterReading(
                    timestamp = System.currentTimeMillis(),
                    identification = "${ident.manufacturer} ${ident.text}".trim(),
                    values = values,
                    raw = frame.payload,
                )
            }
            is FrameResult.BadChecksum ->
                throw IOException("Error de checksum (BCC) en la trama. Reintenta la lectura.")
            null -> throw IOException("Trama incompleta.")
        }
    }

    private fun setBaud(baud: Int) {
        port.setParameters(baud, 7, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_EVEN)
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 1000
        const val IDENT_TIMEOUT_MS = 4000L
        const val DATA_TIMEOUT_MS = 20000L

        // Si tras el ACK no llega ni un byte en este tiempo, el contador no va a enviar la lectura.
        const val SILENT_AFTER_ACK_MS = 6000L
    }
}
