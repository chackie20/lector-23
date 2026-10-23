package es.lectorcontador

import android.os.SystemClock
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException
import java.util.concurrent.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Resultado de la negociación inicial hacia el modo E: identificación del contador y velocidad acordada. */
class ModeEResult(val ident: Identification, val baud: Int, val identBytes: ByteArray) {
    val text: String get() = "${ident.manufacturer} ${ident.text}".trim()
}

/**
 * Arranque habitual de los contadores inteligentes por el puerto óptico (IEC 62056-21, modo E):
 *  1. "/?!\r\n" a 300 baudios, 7E1;   2. el contador responde "/XXXZ<identificación>";
 *  3. ACK 06 '2' Z '2' CR LF (protocolo de control 2 = HDLC / DLMS);   4. ambos cambian a la velocidad Z, 8N1.
 */
object IecModeE {
    private const val IDENT_TIMEOUT_MS = 4000L

    suspend fun open(
        port: UsbSerialPort,
        status: (String) -> Unit = {},
        log: ((String) -> Unit)? = null,
    ): ModeEResult {
        port.setParameters(Iec62056.INITIAL_BAUD, 7, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_EVEN)
        runCatching { port.purgeHwBuffers(true, true) }

        status("Despertando al contador (IEC 62056-21)…")
        var identBytes: ByteArray? = null
        for (attempt in 1..2) {
            port.write(Iec62056.REQUEST, 1000)
            val r = port.collect(IDENT_TIMEOUT_MS) { Iec62056.findIdentification(asciiOf(it)) != null }
            if (r.complete) {
                identBytes = r.bytes
                break
            }
            if (r.bytes.isNotEmpty()) log?.invoke("Intento $attempt: llegaron bytes pero no una identificación: ${ByteFormat.ascii(r.bytes)}")
        }
        if (identBytes == null) {
            throw IOException("El contador no responde. Comprueba que la sonda está bien alineada con el puerto óptico.")
        }
        val ident = Iec62056.findIdentification(asciiOf(identBytes))!!
        val baud = Iec62056.baudFor(ident.baudChar) ?: 9600
        log?.invoke("Identificación: ${ByteFormat.ascii(identBytes)}")
        log?.invoke("   HEX: ${ByteFormat.hex(identBytes)}")
        log?.invoke("   Fabricante ${ident.manufacturer}, velocidad propuesta $baud baudios.")

        // El estándar pide esperar un poco antes del ACK; el ACK a 300 baudios tarda ~220 ms en salir.
        delay(300)
        port.write(DlmsSetup.modeEAck(ident.baudChar), 1000)
        delay(350)
        port.setParameters(baud, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        runCatching { port.purgeHwBuffers(true, true) }
        return ModeEResult(ident, baud, identBytes)
    }
}

/** Tramas HDLC por el puerto serie. Ignora lo que no esté dirigido al cliente (p. ej. el eco de la sonda). */
class SerialHdlc(
    private val port: UsbSerialPort,
    private val alive: () -> Boolean,
) : HdlcTransport {
    private var buffer = ByteArray(0)
    private val queue = ArrayDeque<HdlcFrame>()

    /** Bytes recibidos durante la última espera, válidos o no (para el diagnóstico). */
    var lastRaw: ByteArray = ByteArray(0)
        private set

    override fun send(bytes: ByteArray) {
        port.write(bytes, 1000)
    }

    override fun receive(timeoutMs: Long, accept: (HdlcFrame) -> Boolean): HdlcFrame? {
        lastRaw = ByteArray(0)
        takeAccepted(accept)?.let { return it }
        val tmp = ByteArray(512)
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!alive()) throw CancellationException("Cancelado")
            val n = port.read(tmp, 100)
            if (n > 0) {
                val chunk = tmp.copyOf(n)
                lastRaw += chunk
                val ex = Hdlc.extract(buffer + chunk)
                buffer = ex.rest
                ex.frames.forEach { queue.addLast(it) }
                takeAccepted(accept)?.let { return it }
            }
        }
        return null
    }

    private fun takeAccepted(accept: (HdlcFrame) -> Boolean): HdlcFrame? {
        while (queue.isNotEmpty()) {
            val f = queue.removeFirst()
            if (accept(f)) return f
        }
        return null
    }
}

/** Abre una sesión DLMS completa: modo E, enlace HDLC (SNRM/UA) y asociación (AARQ/AARE). */
class DlmsSession(
    private val port: UsbSerialPort,
    private val cfg: MeterConfig,
    private val status: (String) -> Unit = {},
    private val alive: () -> Boolean = { true },
) {
    /** Identificación del contador (la que anunció en IEC 62056-21). */
    var identification: String = ""
        private set

    /** Dirección de servidor con la que se abrió el enlace. */
    var server: HdlcAddress? = null
        private set

    val password: String? get() = cfg.dlmsPassword.ifBlank { null }

    suspend fun open(): DlmsClient {
        val mode = IecModeE.open(port, status)
        identification = mode.text
        val io = SerialHdlc(port, alive)
        val client = HdlcAddress(cfg.dlmsClient)

        for (candidate in DlmsSetup.serverCandidates(cfg.dlmsAutoServer, cfg.dlmsServerLogical, cfg.dlmsServerPhysical)) {
            val dlms = DlmsClient(io, client, candidate)
            status("Abriendo el enlace HDLC con el servidor $candidate…")
            if (!dlms.connect()) continue

            status("Asociando (cliente ${cfg.dlmsClient}, ${if (password != null) "con contraseña" else "sin contraseña"})…")
            val aare = try {
                dlms.associate(password)
            } catch (e: IOException) {
                dlms.disconnect()
                throw e
            }
            if (aare.result != 0) {
                dlms.disconnect()
                throw IOException(
                    "El contador rechazó la asociación con el cliente ${cfg.dlmsClient}: ${Dlms.aareFailureText(aare)}. " +
                        "Revisa el cliente y la contraseña en Ajustes; el Diagnóstico DLMS prueba los clientes habituales."
                )
            }
            server = candidate
            return dlms
        }
        throw IOException(
            "El contador (${mode.text}) contestó a la identificación, pero no abre el enlace HDLC a " +
                "${mode.baud} baudios. Prueba el Diagnóstico DLMS y comparte el informe."
        )
    }
}

/** El enlace y la asociación funcionan, pero el contador no entrega los datos pedidos a este cliente. */
class DlmsAccessException(message: String) : IOException(message)

/** Sesión DLMS abierta (puerto ya configurado): se puede reutilizar entre lecturas del modo en vivo. */
class DlmsLive(val session: DlmsSession, val dlms: DlmsClient) {
    fun close() = dlms.disconnect()
}

/** Lectura de un contador por DLMS/COSEM a través del puerto óptico. */
class MeterDlmsLink(
    private val port: UsbSerialPort,
    private val cfg: MeterConfig,
    private val mode: ReadMode,
    private val cache: MutableMap<String, Pair<Int, Int>>,
    private val unavailable: MutableMap<String, Int> = HashMap(),
    private val onStatus: (String) -> Unit = {},
    private val alive: () -> Boolean = { true },
) {
    /** Negocia el modo E, abre el enlace HDLC y asocia. */
    suspend fun open(check: () -> Boolean = alive): DlmsLive = withContext(Dispatchers.IO) {
        val session = DlmsSession(port, cfg, onStatus, check)
        DlmsLive(session, session.open())
    }

    /** Lectura completa con una sesión ya abierta. */
    suspend fun read(live: DlmsLive): MeterReading = withContext(Dispatchers.IO) {
        val session = live.session
        val dlms = live.dlms
        var specs = DlmsCatalog.forMode(mode)
        if (mode == ReadMode.TODO) specs = specs + discoverExtras(dlms, specs)

        val res = DlmsReader.read(dlms, specs, cache, onStatus, unavailable)
        if (res.values.isEmpty()) throw noEnergyError(session, res)

        val server = session.server
        val summary = "cliente ${cfg.dlmsClient}, servidor ${server ?: "?"}: ${res.values.size} valores" +
            (if (res.denied > 0) ", ${res.denied} denegados" else "") +
            (if (res.missing > 0) ", ${res.missing} inexistentes" else "")
        MeterReading(
            timestamp = System.currentTimeMillis(),
            identification = "DLMS · ${session.identification}",
            values = res.values,
            raw = summary,
            warning = if (res.scaledRead == 0) partialWarning(session, res) else null,
        )
    }

    /** Lectura de una sola vez: abre, lee y cierra. */
    suspend fun readOut(): MeterReading {
        val job = coroutineContext[Job]
        val live = open { alive() && job?.isActive != false }
        try {
            return read(live)
        } finally {
            withContext(Dispatchers.IO) { live.close() }
        }
    }

    /** En el modo "Todo": registros que el contador anuncia y el catálogo estándar no incluye. */
    private fun discoverExtras(dlms: DlmsClient, known: List<DlmsObject>): List<DlmsObject> {
        onStatus("Leyendo la lista de objetos del contador…")
        return try {
            val r = dlms.get(Dlms.CLASS_ASSOCIATION_LN, DlmsDiscovery.ASSOCIATION_OBIS, 2)
            val data = (r as? GetResult.Value)?.data ?: return emptyList()
            DlmsDiscovery.extraSpecs(DlmsDiscovery.parseObjectList(data), known)
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            emptyList() // sin la lista se lee solo el catálogo estándar
        }
    }

    private fun who(session: DlmsSession) =
        "cliente ${cfg.dlmsClient}${if (session.password == null) ", sin contraseña" else ""}"

    /** Aviso de lectura parcial: hay identificación pero ninguna energía. */
    private fun partialWarning(session: DlmsSession, res: DlmsReadResult): String = when {
        res.denied > 0 ->
            "Lectura parcial: el contador solo entrega la identificación al ${who(session)} y deniega la energía. " +
                "Hace falta el cliente y la contraseña de lectura que fija la distribuidora (Ajustes)."
        res.missing > 0 ->
            "Lectura parcial: el contador no tiene los registros OBIS estándar de energía. " +
                "«Listar objetos» en Diagnóstico muestra cuáles expone."
        else -> "Lectura parcial: no se pudo leer ninguna energía" + (res.failed.firstOrNull()?.let { " ($it)" } ?: ".")
    }

    private fun noEnergyError(session: DlmsSession, res: DlmsReadResult): IOException = when {
        res.denied > 0 -> DlmsAccessException(
            "Enlace y asociación correctos, pero el contador deniega todo al ${who(session)}. " +
                "Hace falta el cliente y la contraseña de lectura, que facilita la distribuidora (Ajustes)."
        )
        res.missing > 0 -> DlmsAccessException(
            "Enlace y asociación correctos, pero el contador no tiene los registros OBIS estándar. " +
                "Usa «Listar objetos» en Diagnóstico para ver cuáles expone y compártelo."
        )
        else -> IOException(
            "Enlace correcto, pero no se pudo leer ningún valor" +
                (res.failed.firstOrNull()?.let { ": $it" } ?: ".")
        )
    }
}
