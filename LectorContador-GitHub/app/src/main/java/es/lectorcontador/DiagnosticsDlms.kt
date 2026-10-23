package es.lectorcontador

import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/** Cliente y servidor con los que el contador aceptó la asociación. */
class DlmsFound(val client: Int, val server: HdlcAddress, val energyReadable: Boolean)

/**
 * Diagnóstico de DLMS/COSEM por el puerto óptico. Solo hace lecturas: negociación IEC 62056-21, SNRM,
 * asociación y GET. Los clientes distintos del configurado se prueban siempre SIN contraseña, y no se
 * prueban contraseñas: la de Ajustes se usa solo con el cliente configurado.
 */
class MeterDlmsDiagnostics(
    private val port: UsbSerialPort,
    private val cfg: MeterConfig,
    private val log: (String) -> Unit,
) {
    suspend fun run(passwordAll: Boolean = false): DlmsFound? = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        val alive = { job?.isActive != false }
        val dev = port.driver.device
        if (passwordAll) {
            log("Se prueba la contraseña de Ajustes UNA sola vez con cada cliente (no se prueban otras contraseñas).")
        }
        log("Sonda USB: VID 0x%04X, PID 0x%04X".format(dev.vendorId, dev.productId))
        log("")
        log("1) Negociación IEC 62056-21 a 300 baudios (7E1) hacia el modo E (HDLC)")
        val mode = try {
            IecModeE.open(port, {}, { log("   $it") })
        } catch (e: IOException) {
            log("   ${e.message}")
            return@withContext null
        }
        log("   Cambio a ${mode.baud} baudios, 8N1.")
        val io = SerialHdlc(port, alive)

        log("")
        log("2) Enlace HDLC (SNRM): se prueban las direcciones de servidor habituales")
        val servers = (
            DlmsSetup.serverCandidates(true, 1, 16) +
                DlmsSetup.serverCandidates(false, cfg.dlmsServerLogical, cfg.dlmsServerPhysical)
            ).distinct()
        var server: HdlcAddress? = null
        for (s in servers) {
            val probe = DlmsClient(io, HdlcAddress(16), s)
            if (probe.connect(attempts = 1, timeoutMs = 1500)) {
                log("   servidor $s: UA recibido ✔")
                probe.disconnect()
                server = s
                break
            }
            val raw = io.lastRaw
            log(
                "   servidor $s: " +
                    if (raw.isEmpty()) "sin respuesta" else "llegan ${raw.size} bytes que no son un UA válido: ${ByteFormat.hex(raw)}"
            )
            delay(200)
        }
        if (server == null) {
            log("")
            log(
                "RESULTADO: el contador identificó (${mode.text}) pero no abre el enlace HDLC a ${mode.baud} baudios. " +
                    "Puede que este puerto no hable DLMS, que la velocidad no coincida o que la sonda no reciba bien. " +
                    "Comparte este informe."
            )
            return@withContext null
        }

        log("")
        log("3) Asociación DLMS: se prueban los clientes habituales (sin contraseña, salvo el configurado)")
        var best: DlmsFound? = null
        val clients = if (passwordAll) {
            listOf(cfg.dlmsClient, 1, 2, 3, 17, 32, 48).filter { it != 16 }.distinct()
        } else {
            listOf(cfg.dlmsClient, 16, 1, 2, 17, 32, 48).distinct()
        }
        for (c in clients) {
            delay(300)
            val dlms = DlmsClient(io, HdlcAddress(c), server)
            if (!dlms.connect(attempts = 1, timeoutMs = 1500)) {
                log("   cliente $c: el contador no abre el enlace")
                continue
            }
            val pw = if (passwordAll || c == cfg.dlmsClient) cfg.dlmsPassword.ifBlank { null } else null
            val aare = try {
                dlms.associate(pw)
            } catch (e: IOException) {
                log("   cliente $c: ${e.message}")
                dlms.disconnect()
                continue
            }
            if (aare.result != 0) {
                log("   cliente $c${if (pw != null) " (con la contraseña de Ajustes)" else ""}: rechazado, ${Dlms.aareFailureText(aare)}")
                dlms.disconnect()
                continue
            }
            log("   cliente $c: asociación aceptada ✔${if (pw != null) " (con la contraseña de Ajustes)" else ""}")
            val readable = probeReads(dlms)
            dlms.disconnect()
            val found = DlmsFound(c, server, readable)
            if (readable) {
                best = found
                break
            }
            if (best == null) best = found
        }

        log("")
        val b = best
        when {
            b == null -> log(
                "RESULTADO: el enlace abre (servidor $server), pero ningún cliente se asocia sin contraseña. " +
                    "La distribuidora fija el cliente y la contraseña de lectura (LLS): pídelos y ponlos en Ajustes."
            )
            b.energyReadable -> log(
                "RESULTADO: el cliente ${b.client} con el servidor ${b.server} puede leer la energía. Guardado en Ajustes."
            )
            else -> log(
                "RESULTADO: el cliente ${b.client} se asocia, pero no tiene acceso a la energía. " +
                    "Hace falta la contraseña de lectura de la distribuidora. Cliente y servidor guardados en Ajustes."
            )
        }
        b
    }

    private fun describe(r: GetResult): String = when (r) {
        is GetResult.Value -> Dlms.plainText(r.data) ?: r.data.toString()
        is GetResult.Failure -> "no (${r.text})"
        is GetResult.Block -> "respuesta por bloques"
    }

    /** Lee el nº de serie y la energía activa importada; true si la energía se pudo leer. */
    private fun probeReads(dlms: DlmsClient): Boolean = try {
        val serial = dlms.get(Dlms.CLASS_DATA, Dlms.obis(0, 0, 96, 1, 1), 2)
        log("      nº de serie (0-0:96.1.1): ${describe(serial)}")
        var energy = dlms.get(Dlms.CLASS_REGISTER, Dlms.obis(1, 0, 1, 8, 0), 2)
        if (energy is GetResult.Failure && Dlms.isMissing(energy.code)) {
            energy = dlms.get(Dlms.CLASS_REGISTER, Dlms.obis(1, 1, 1, 8, 0), 2)
        }
        log("      energía activa importada (1-0:1.8.0): ${describe(energy)}")
        energy is GetResult.Value
    } catch (e: IOException) {
        log("      lectura interrumpida: ${e.message}")
        false
    }

    /** Muestra la lista de objetos que el contador entrega al cliente configurado. */
    suspend fun listObjects() = withContext(Dispatchers.IO) {
        val job = coroutineContext[Job]
        log("Lista de objetos del contador (asociación 0-0:40.0.0.255)")
        val session = DlmsSession(port, cfg, { log("   $it") }) { job?.isActive != false }
        val dlms = try {
            session.open()
        } catch (e: IOException) {
            log("Error: ${e.message}")
            return@withContext
        }
        try {
            log("Asociado como cliente ${cfg.dlmsClient} (${session.identification}). Leyendo la lista…")
            when (val r = dlms.get(Dlms.CLASS_ASSOCIATION_LN, DlmsDiscovery.ASSOCIATION_OBIS, 2)) {
                is GetResult.Failure -> log("El contador no entrega la lista a este cliente: ${r.text}")
                is GetResult.Block -> log("Respuesta por bloques sin completar.")
                is GetResult.Value -> {
                    val objs = DlmsDiscovery.parseObjectList(r.data)
                    val counts = objs.groupingBy { it.classId }.eachCount().toSortedMap()
                    log("${objs.size} objetos. Por clase: " + counts.entries.joinToString(", ") { "${it.key}×${it.value}" })
                    log("Datos, registros y reloj (clases 1, 3, 4 y 8):")
                    objs.filter { it.classId == 1 || it.classId == 3 || it.classId == 4 || it.classId == 8 }
                        .forEach { log("  clase ${it.classId}  ${it.obis}") }
                }
            }
        } catch (e: IOException) {
            log("Error: ${e.message}")
        } finally {
            dlms.disconnect()
        }
    }
}
