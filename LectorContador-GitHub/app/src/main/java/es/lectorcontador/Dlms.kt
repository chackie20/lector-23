package es.lectorcontador

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.math.BigDecimal

/*
 * Capa de enlace HDLC (IEC 62056-46) y los mensajes DLMS/COSEM mínimos para leer registros por el
 * puerto óptico: SNRM/UA, asociación (AARQ/AARE) con o sin contraseña (LLS) y GET con referencia
 * por nombre lógico. Sin dependencias de Android, para poder probarlo con JUnit en el PC.
 *
 * Trama HDLC:  7E | A0+long(2) | dirección destino | dirección origen | control | HCS(2) | [info | FCS(2)] | 7E
 *  - Sin campo de información solo hay un CRC (HCS), que hace también de FCS.
 *  - CRC-16/X.25, se transmite primero el byte bajo.
 *  - Direcciones: 1 o 2 octetos; cada octeto lleva 7 bits y el bit 0 vale 1 en el último.
 */

object Crc16 {
    /** CRC-16/X.25 (ISO 13239): el de HDLC. */
    fun x25(data: ByteArray, from: Int = 0, to: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in from until to) {
            crc = crc xor (data[i].toInt() and 0xFF)
            repeat(8) { crc = if ((crc and 1) != 0) (crc ushr 1) xor 0x8408 else crc ushr 1 }
        }
        return crc.inv() and 0xFFFF
    }
}

/** Dirección HDLC: parte alta (lógica) y, opcionalmente, parte baja (física). */
data class HdlcAddress(val upper: Int, val lower: Int? = null) {
    fun encode(): ByteArray {
        require(upper in 0..127 && (lower == null || lower in 0..127)) { "dirección HDLC fuera de rango (0–127)" }
        return if (lower == null) {
            byteArrayOf(((upper shl 1) or 1).toByte())
        } else {
            byteArrayOf((upper shl 1).toByte(), ((lower shl 1) or 1).toByte())
        }
    }

    override fun toString() = if (lower == null) "$upper" else "$upper/$lower"

    companion object {
        /** Decodifica 1, 2 o 4 octetos; null si la longitud no es válida. */
        fun decode(b: ByteArray): HdlcAddress? {
            fun v(i: Int) = (b[i].toInt() and 0xFF) ushr 1
            return when (b.size) {
                1 -> HdlcAddress(v(0))
                2 -> HdlcAddress(v(0), v(1))
                4 -> HdlcAddress((v(0) shl 7) or v(1), (v(2) shl 7) or v(3))
                else -> null
            }
        }
    }
}

class HdlcFrame(
    val dst: HdlcAddress,
    val src: HdlcAddress,
    val control: Int,
    val info: ByteArray,
    val segmented: Boolean,
) {
    /** Trama de información (I): bit 0 del control a 0. */
    val isInfo: Boolean get() = (control and 0x01) == 0
    val isSupervisory: Boolean get() = (control and 0x03) == 0x01
    val isUnnumbered: Boolean get() = (control and 0x03) == 0x03
    val ns: Int get() = (control shr 1) and 0x07
    val nr: Int get() = (control shr 5) and 0x07
    val isUa: Boolean get() = isUnnumbered && (control and 0xEF) == 0x63
    val isDm: Boolean get() = isUnnumbered && (control and 0xEF) == 0x0F
    val isFrmr: Boolean get() = isUnnumbered && (control and 0xEF) == 0x87

    override fun toString() =
        "Trama(dst=$dst, src=$src, control=0x%02X, info=%d bytes%s)".format(control, info.size, if (segmented) ", segmentada" else "")
}

object Hdlc {
    const val FLAG = 0x7E
    const val SNRM = 0x93
    const val DISC = 0x53

    /** Cabecera LLC de las peticiones del cliente. */
    val LLC_COMMAND = byteArrayOf(0xE6.toByte(), 0xE6.toByte(), 0x00)

    fun infoControl(vs: Int, vr: Int): Int = ((vr and 7) shl 5) or 0x10 or ((vs and 7) shl 1)

    /** RR (receptor preparado) con el siguiente número esperado. */
    fun rr(vr: Int): Int = ((vr and 7) shl 5) or 0x11

    fun build(
        dst: HdlcAddress,
        src: HdlcAddress,
        control: Int,
        info: ByteArray = ByteArray(0),
        segmented: Boolean = false,
    ): ByteArray {
        val addr = dst.encode() + src.encode()
        val len = 2 + addr.size + 1 + 2 + (if (info.isEmpty()) 0 else info.size + 2)
        require(len <= 0x7FF) { "trama HDLC demasiado larga" }
        val header = ByteArray(2 + addr.size + 1)
        header[0] = (0xA0 or (if (segmented) 0x08 else 0) or ((len shr 8) and 0x07)).toByte()
        header[1] = (len and 0xFF).toByte()
        addr.copyInto(header, 2)
        header[header.size - 1] = control.toByte()
        val hcs = Crc16.x25(header)
        var body = header + byteArrayOf((hcs and 0xFF).toByte(), (hcs shr 8).toByte())
        if (info.isNotEmpty()) {
            body += info
            val fcs = Crc16.x25(body)
            body += byteArrayOf((fcs and 0xFF).toByte(), (fcs shr 8).toByte())
        }
        return byteArrayOf(FLAG.toByte()) + body + byteArrayOf(FLAG.toByte())
    }

    class Extracted(val frames: List<HdlcFrame>, val rest: ByteArray)

    /**
     * Extrae las tramas válidas del búfer (descarta basura y tramas con CRC erróneo).
     * [Extracted.rest] es lo que queda sin procesar: una trama aún incompleta.
     */
    fun extract(buf: ByteArray): Extracted {
        // Se delimita por el campo de longitud, no buscando la siguiente bandera: el 0x7E puede aparecer
        // dentro de los datos (p. ej. en el bloque de conformidad del AARQ: 00 7E 1F).
        val frames = mutableListOf<HdlcFrame>()
        val flag = FLAG.toByte()
        var i = 0
        while (true) {
            var start = i
            while (start < buf.size && buf[start] != flag) start++
            if (start >= buf.size) return Extracted(frames, ByteArray(0))
            if (start + 2 >= buf.size) return Extracted(frames, buf.copyOfRange(start, buf.size))
            val b0 = buf[start + 1].toInt() and 0xFF
            if ((b0 and 0xF0) != 0xA0) {     // bandera repetida o basura: la siguiente puede abrir la trama
                i = start + 1
                continue
            }
            val len = ((b0 and 0x07) shl 8) or (buf[start + 2].toInt() and 0xFF)
            val end = start + 1 + len        // posición de la bandera de cierre
            if (len < 7) {
                i = start + 1
                continue
            }
            if (end >= buf.size) return Extracted(frames, buf.copyOfRange(start, buf.size))
            val frame = if (buf[end] == flag) parse(buf.copyOfRange(start + 1, end)) else null
            if (frame == null) {
                i = start + 1
                continue
            }
            frames += frame
            i = end                          // la bandera de cierre puede abrir la trama siguiente
        }
    }

    private fun parse(c: ByteArray): HdlcFrame? {
        if (c.size < 7) return null
        val b0 = c[0].toInt() and 0xFF
        if ((b0 and 0xF0) != 0xA0) return null
        val len = ((b0 and 0x07) shl 8) or (c[1].toInt() and 0xFF)
        if (len != c.size) return null

        var p = 2
        fun readAddress(): HdlcAddress? {
            val start = p
            while (p < c.size && (c[p].toInt() and 1) == 0) p++
            if (p >= c.size) return null
            p++ // incluye el octeto con el bit 0 a 1
            return HdlcAddress.decode(c.copyOfRange(start, p))
        }

        val dst = readAddress() ?: return null
        val src = readAddress() ?: return null
        if (p >= c.size) return null
        val control = c[p].toInt() and 0xFF
        val headerEnd = p + 1
        if (c.size < headerEnd + 2) return null
        val hcs = (c[headerEnd].toInt() and 0xFF) or ((c[headerEnd + 1].toInt() and 0xFF) shl 8)
        if (Crc16.x25(c, 0, headerEnd) != hcs) return null

        val segmented = (b0 and 0x08) != 0
        if (c.size == headerEnd + 2) return HdlcFrame(dst, src, control, ByteArray(0), segmented)
        if (c.size < headerEnd + 4) return null
        val fcs = (c[c.size - 2].toInt() and 0xFF) or ((c[c.size - 1].toInt() and 0xFF) shl 8)
        if (Crc16.x25(c, 0, c.size - 2) != fcs) return null
        return HdlcFrame(dst, src, control, c.copyOfRange(headerEnd + 2, c.size - 2), segmented)
    }
}

/** Datos COSEM ya decodificados. */
sealed interface DlmsData {
    object Null : DlmsData {
        override fun toString() = "null"
    }

    data class Num(val value: Long) : DlmsData
    data class Flt(val value: Double) : DlmsData
    data class Text(val value: String) : DlmsData
    class Bytes(val value: ByteArray) : DlmsData {
        override fun toString() = "Bytes(${ByteFormat.hex(value)})"
        override fun equals(other: Any?) = other is Bytes && other.value.contentEquals(value)
        override fun hashCode() = value.contentHashCode()
    }

    data class Items(val items: List<DlmsData>) : DlmsData
}

sealed interface GetResult {
    class Value(val data: DlmsData) : GetResult
    class Failure(val code: Int, val detail: String? = null) : GetResult {
        val text: String get() = detail ?: Dlms.accessErrorText(code)
    }

    /** Bloque intermedio de una respuesta larga (solo lo usa el cliente por dentro). */
    class Block(val last: Boolean, val number: Long, val raw: ByteArray) : GetResult
}

class Aare(val result: Int, val diagnostic: Int?)

object Dlms {
    const val CLASS_DATA = 1
    const val CLASS_REGISTER = 3
    const val CLASS_EXT_REGISTER = 4
    const val CLASS_CLOCK = 8
    const val CLASS_ASSOCIATION_LN = 15

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF

    fun bytesOf(hex: String): ByteArray =
        hex.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }.map { it.toInt(16).toByte() }.toByteArray()

    fun obis(a: Int, b: Int, c: Int, d: Int, e: Int, f: Int = 255): ByteArray =
        byteArrayOf(a.toByte(), b.toByte(), c.toByte(), d.toByte(), e.toByte(), f.toByte())

    /** Quita la cabecera LLC (E6 E7 00) de las respuestas del contador. */
    fun stripLlc(info: ByteArray): ByteArray =
        if (info.size >= 3 && u(info, 0) == 0xE6 && (u(info, 1) == 0xE7 || u(info, 1) == 0xE6) && u(info, 2) == 0x00) {
            info.copyOfRange(3, info.size)
        } else {
            info
        }

    // ───────────────────────── Asociación ─────────────────────────

    /**
     * AARQ con contexto de aplicación "nombre lógico sin cifrado". Con [password] != null pide autenticación
     * de baja seguridad (LLS) y envía la contraseña en claro (ASCII).
     */
    fun passwordBytes(password: String): ByteArray {
        val p = password.trim()
        val h = p.removePrefix("0x").removePrefix("0X")
        if (h.length < p.length && h.isNotEmpty() && h.length % 2 == 0 && h.all { it in "0123456789abcdefABCDEF" }) {
            return h.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        return password.toByteArray(Charsets.US_ASCII)
    }

    fun aarq(password: String?): ByteArray {
        val context = bytesOf("A1 09 06 07 60 85 74 05 08 01 01")
        val auth = if (password == null) {
            ByteArray(0)
        } else {
            val pw = passwordBytes(password)
            require(pw.size <= 32) { "contraseña demasiado larga" }
            bytesOf("8A 02 07 80") + bytesOf("8B 07 60 85 74 05 08 02 01") +
                byteArrayOf(0xAC.toByte(), (pw.size + 2).toByte(), 0x80.toByte(), pw.size.toByte()) + pw
        }
        // InitiateRequest: sin clave dedicada, versión DLMS 6, conformidad 00 7E 1F, PDU máximo 1200
        val user = bytesOf("BE 10 04 0E 01 00 00 00 06 5F 1F 04 00 00 7E 1F 04 B0")
        val body = context + auth + user
        return if (body.size < 0x80) {
            byteArrayOf(0x60, body.size.toByte()) + body
        } else {
            byteArrayOf(0x60, 0x81.toByte(), body.size.toByte()) + body
        }
    }

    fun readLength(b: ByteArray, off: Int): Pair<Int, Int>? {
        if (off >= b.size) return null
        val f = u(b, off)
        return when {
            f < 0x80 -> f to (off + 1)
            f == 0x81 && off + 1 < b.size -> u(b, off + 1) to (off + 2)
            f == 0x82 && off + 2 < b.size -> ((u(b, off + 1) shl 8) or u(b, off + 2)) to (off + 3)
            else -> null
        }
    }

    fun parseAare(apdu: ByteArray): Aare? {
        if (apdu.isEmpty() || u(apdu, 0) != 0x61) return null
        val (len, start) = readLength(apdu, 1) ?: return null
        val end = minOf(apdu.size, start + len)
        var p = start
        var result: Int? = null
        var diagnostic: Int? = null
        while (p < end) {
            val tag = u(apdu, p)
            val (l, vp) = readLength(apdu, p + 1) ?: break
            if (vp + l > end) break
            if (tag == 0xA2 && l >= 3) result = u(apdu, vp + 2)           // 02 01 resultado
            if (tag == 0xA3 && l >= 5) diagnostic = u(apdu, vp + 4)       // A1/A2 03 02 01 diagnóstico
            p = vp + l
        }
        return result?.let { Aare(it, diagnostic) }
    }

    fun aareFailureText(a: Aare): String = when (a.diagnostic) {
        13 -> "contraseña incorrecta"
        14 -> "el contador exige autenticación (contraseña) para este cliente"
        12 -> "el contador exige un mecanismo de autenticación"
        11 -> "mecanismo de autenticación no reconocido"
        2 -> "contexto de aplicación no soportado"
        else -> "resultado ${a.result}, diagnóstico ${a.diagnostic ?: "-"}"
    }

    // ───────────────────────── GET ─────────────────────────

    fun getRequest(invokeId: Int, classId: Int, obis: ByteArray, attribute: Int): ByteArray {
        require(obis.size == 6) { "el código OBIS tiene 6 octetos" }
        return byteArrayOf(
            0xC0.toByte(), 0x01, (0xC0 or (invokeId and 0x0F)).toByte(),
            (classId shr 8).toByte(), classId.toByte(),
        ) + obis + byteArrayOf(attribute.toByte(), 0x00)
    }

    fun getNextRequest(invokeId: Int, block: Long): ByteArray = byteArrayOf(
        0xC0.toByte(), 0x02, (0xC0 or (invokeId and 0x0F)).toByte(),
        (block shr 24).toByte(), (block shr 16).toByte(), (block shr 8).toByte(), block.toByte(),
    )

    private fun be(b: ByteArray, off: Int, n: Int): Long {
        var v = 0L
        for (i in 0 until n) v = (v shl 8) or u(b, off + i).toLong()
        return v
    }

    fun parseGetResponse(apdu: ByteArray): GetResult? {
        if (apdu.size < 2) return null
        when (u(apdu, 0)) {
            0xC4 -> {
                when (u(apdu, 1)) {
                    0x01 -> { // normal
                        if (apdu.size < 5) return null
                        return when (u(apdu, 3)) {
                            0x00 -> parseData(apdu, 4)?.let { GetResult.Value(it.first) }
                            0x01 -> GetResult.Failure(u(apdu, 4))
                            else -> null
                        }
                    }
                    0x02 -> { // con bloques: C4 02 id último(1) nº bloque(4) resultado(1) ...
                        if (apdu.size < 10) return null
                        val last = u(apdu, 3) != 0
                        val number = be(apdu, 4, 4)
                        return when (u(apdu, 8)) {
                            0x00 -> {
                                val (len, p) = readLength(apdu, 9) ?: return null
                                if (p + len > apdu.size) return null
                                GetResult.Block(last, number, apdu.copyOfRange(p, p + len))
                            }
                            0x01 -> GetResult.Failure(u(apdu, 9))
                            else -> null
                        }
                    }
                }
                return null
            }
            0xD8 -> { // ExceptionResponse: estado, servicio
                if (apdu.size < 3) return null
                return GetResult.Failure(
                    1000 + u(apdu, 1) * 10 + u(apdu, 2),
                    "excepción del contador (estado ${u(apdu, 1)}, servicio ${u(apdu, 2)})",
                )
            }
        }
        return null
    }

    fun accessErrorText(code: Int): String = when (code) {
        1 -> "fallo de hardware"
        2 -> "fallo temporal"
        3 -> "acceso denegado"
        4 -> "objeto inexistente"
        9 -> "clase de objeto incompatible"
        11 -> "objeto no disponible"
        12 -> "tipo de dato no coincide"
        13 -> "fuera del alcance de acceso del cliente"
        250 -> "otra razón"
        else -> "código $code"
    }

    fun isDenied(code: Int) = code == 3 || code == 13
    fun isMissing(code: Int) = code == 4 || code == 9 || code == 11

    // ───────────────────────── Datos ─────────────────────────

    /** Decodifica un dato COSEM. Devuelve el dato y la posición siguiente, o null si no se entiende. */
    fun parseData(b: ByteArray, off: Int = 0): Pair<DlmsData, Int>? {
        if (off >= b.size) return null
        val tag = u(b, off)
        var p = off + 1
        fun has(n: Int) = p + n <= b.size
        when (tag) {
            0x00 -> return DlmsData.Null to p
            0x01, 0x02 -> {
                val (count, np) = readLength(b, p) ?: return null
                p = np
                val items = ArrayList<DlmsData>()
                for (k in 0 until count) {
                    val (d, next) = parseData(b, p) ?: return null
                    items += d
                    p = next
                }
                return DlmsData.Items(items) to p
            }
            0x03 -> { if (!has(1)) return null; return DlmsData.Num(u(b, p).toLong()) to (p + 1) }
            0x04 -> {
                val (bits, np) = readLength(b, p) ?: return null
                val n = (bits + 7) / 8
                if (np + n > b.size) return null
                return DlmsData.Bytes(b.copyOfRange(np, np + n)) to (np + n)
            }
            0x05 -> { if (!has(4)) return null; return DlmsData.Num(be(b, p, 4).toInt().toLong()) to (p + 4) }
            0x06 -> { if (!has(4)) return null; return DlmsData.Num(be(b, p, 4)) to (p + 4) }
            0x09, 0x0A, 0x0C -> {
                val (len, np) = readLength(b, p) ?: return null
                if (np + len > b.size) return null
                val raw = b.copyOfRange(np, np + len)
                val d = when (tag) {
                    0x09 -> DlmsData.Bytes(raw)
                    0x0A -> DlmsData.Text(String(raw, Charsets.US_ASCII))
                    else -> DlmsData.Text(String(raw, Charsets.UTF_8))
                }
                return d to (np + len)
            }
            0x0D, 0x11, 0x16 -> { if (!has(1)) return null; return DlmsData.Num(u(b, p).toLong()) to (p + 1) }
            0x0F -> { if (!has(1)) return null; return DlmsData.Num(b[p].toLong()) to (p + 1) }
            0x10 -> { if (!has(2)) return null; return DlmsData.Num(be(b, p, 2).toShort().toLong()) to (p + 2) }
            0x12 -> { if (!has(2)) return null; return DlmsData.Num(be(b, p, 2)) to (p + 2) }
            0x14, 0x15 -> { if (!has(8)) return null; return DlmsData.Num(be(b, p, 8)) to (p + 8) }
            0x17 -> {
                if (!has(4)) return null
                return DlmsData.Flt(java.lang.Float.intBitsToFloat(be(b, p, 4).toInt()).toDouble()) to (p + 4)
            }
            0x18 -> {
                if (!has(8)) return null
                return DlmsData.Flt(java.lang.Double.longBitsToDouble(be(b, p, 8))) to (p + 8)
            }
            0x19 -> { if (!has(12)) return null; return DlmsData.Bytes(b.copyOfRange(p, p + 12)) to (p + 12) }
            0x1A -> { if (!has(5)) return null; return DlmsData.Bytes(b.copyOfRange(p, p + 5)) to (p + 5) }
            0x1B -> { if (!has(4)) return null; return DlmsData.Bytes(b.copyOfRange(p, p + 4)) to (p + 4) }
        }
        return null
    }

    fun unitText(code: Int): String = when (code) {
        27 -> "W"
        28 -> "VA"
        29 -> "var"
        30 -> "Wh"
        31 -> "VAh"
        32 -> "varh"
        33 -> "A"
        35 -> "V"
        44 -> "Hz"
        else -> ""
    }

    /** scaler_unit = estructura {escalador, unidad}. */
    fun parseScalerUnit(d: DlmsData): Pair<Int, Int>? {
        if (d !is DlmsData.Items || d.items.size != 2) return null
        val s = d.items[0] as? DlmsData.Num ?: return null
        val unit = d.items[1] as? DlmsData.Num ?: return null
        return s.value.toInt() to unit.value.toInt()
    }

    /** Valor numérico × 10^escalador como texto decimal exacto. */
    fun scaledText(d: DlmsData, scaler: Int): String? = when (d) {
        is DlmsData.Num -> BigDecimal.valueOf(d.value).scaleByPowerOfTen(scaler).toPlainString()
        is DlmsData.Flt -> BigDecimal.valueOf(d.value).scaleByPowerOfTen(scaler).toPlainString()
        else -> null
    }

    /** Texto de un dato de identificación (nº de serie, nombre lógico…). */
    fun plainText(d: DlmsData): String? = when (d) {
        is DlmsData.Num -> d.value.toString()
        is DlmsData.Text -> d.value.trim()
        is DlmsData.Bytes ->
            if (d.value.isNotEmpty() && d.value.all { (it.toInt() and 0xFF) in 0x20..0x7E }) {
                String(d.value, Charsets.US_ASCII).trim()
            } else {
                ByteFormat.hex(d.value, 32)
            }
        else -> null
    }

    /** Reloj COSEM (12 octetos) → fecha "aaaa-mm-dd" y hora "hh:mm:ss"; null si no está especificado. */
    fun clockText(raw: ByteArray): Pair<String, String>? {
        if (raw.size < 8) return null
        val year = (u(raw, 0) shl 8) or u(raw, 1)
        if (year == 0xFFFF || u(raw, 2) !in 1..12 || u(raw, 3) !in 1..31 || u(raw, 5) > 23) return null
        val date = "%04d-%02d-%02d".format(year, u(raw, 2), u(raw, 3))
        val time = "%02d:%02d:%02d".format(u(raw, 5), u(raw, 6), u(raw, 7))
        return date to time
    }
}

// ───────────────────────── Cliente (sesión HDLC + DLMS) ─────────────────────────

/** Entrada/salida de tramas HDLC. La implementa el puerto serie en la app y un contador simulado en los tests. */
interface HdlcTransport {
    fun send(bytes: ByteArray)

    /** Espera una trama que cumpla [accept]; null si se agota el tiempo. */
    fun receive(timeoutMs: Long, accept: (HdlcFrame) -> Boolean): HdlcFrame?
}

class DlmsClient(
    private val io: HdlcTransport,
    val client: HdlcAddress,
    val server: HdlcAddress,
) {
    private var vs = 0
    private var vr = 0
    private var invoke = 0

    private fun fromServer(f: HdlcFrame) = f.dst == client

    /** SNRM → UA. Devuelve false si el contador no contesta o rechaza el enlace. */
    fun connect(attempts: Int = 2, timeoutMs: Long = 1500): Boolean {
        repeat(attempts) {
            io.send(Hdlc.build(server, client, Hdlc.SNRM))
            val f = io.receive(timeoutMs, ::fromServer)
            if (f != null) {
                if (f.isUa) {
                    vs = 0
                    vr = 0
                    return true
                }
                if (f.isDm || f.isFrmr) return false
            }
        }
        return false
    }

    /** Envía una petición (con cabecera LLC) y devuelve el APDU de respuesta completo, uniendo segmentos. */
    private fun exchange(apdu: ByteArray, timeoutMs: Long): ByteArray {
        val frame = Hdlc.build(server, client, Hdlc.infoControl(vs, vr), Hdlc.LLC_COMMAND + apdu)
        vs = (vs + 1) and 7
        var retries = 0
        io.send(frame)
        val out = ByteArrayOutputStream()
        var first = true
        while (true) {
            val f = io.receive(timeoutMs, ::fromServer)
            if (f == null) {
                if (++retries > MAX_RETRIES) throw IOException("El contador no contesta (tiempo agotado).")
                io.send(frame) // retransmite la misma trama
                continue
            }
            when {
                f.isDm || f.isFrmr -> throw IOException("El contador cerró o rechazó el enlace HDLC.")
                !f.isInfo -> continue          // RR/RNR/UA: solo confirman; esperamos la trama de datos
                f.ns != vr -> continue         // duplicada o fuera de secuencia
                else -> {
                    vr = (vr + 1) and 7
                    out.write(if (first) Dlms.stripLlc(f.info) else f.info)
                    first = false
                    if (f.segmented) {
                        io.send(Hdlc.build(server, client, Hdlc.rr(vr)))
                        continue
                    }
                    return out.toByteArray()
                }
            }
        }
    }

    fun associate(password: String?): Aare {
        val resp = exchange(Dlms.aarq(password), 5000)
        return Dlms.parseAare(resp)
            ?: throw IOException("Respuesta de asociación no reconocida: ${ByteFormat.hex(resp, 24)}")
    }

    private fun nextInvoke(): Int {
        invoke = (invoke + 1) and 0x0F
        return invoke
    }

    /** GET de un atributo. Sigue las respuestas por bloques y devuelve un valor o un fallo de acceso. */
    fun get(classId: Int, obis: ByteArray, attribute: Int): GetResult {
        val id = nextInvoke()
        var resp = exchange(Dlms.getRequest(id, classId, obis, attribute), 4000)
        var r = Dlms.parseGetResponse(resp)
            ?: throw IOException("Respuesta DLMS no reconocida: ${ByteFormat.hex(resp, 24)}")
        if (r !is GetResult.Block) return r

        val acc = ByteArrayOutputStream()
        var guard = 0
        while (true) {
            val block = r as? GetResult.Block ?: return r
            acc.write(block.raw)
            if (block.last) break
            if (++guard > MAX_BLOCKS) throw IOException("Respuesta demasiado larga.")
            resp = exchange(Dlms.getNextRequest(id, block.number), 4000)
            r = Dlms.parseGetResponse(resp)
                ?: throw IOException("Respuesta DLMS no reconocida: ${ByteFormat.hex(resp, 24)}")
        }
        val data = Dlms.parseData(acc.toByteArray()) ?: throw IOException("No se pudo interpretar la respuesta por bloques.")
        return GetResult.Value(data.first)
    }

    /** DISC: cierre del enlace. No falla si el contador no contesta. */
    fun disconnect() {
        try {
            io.send(Hdlc.build(server, client, Hdlc.DISC))
            io.receive(800, ::fromServer)
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            // el enlace se cierra de todos modos por inactividad
        }
    }

    private companion object {
        const val MAX_RETRIES = 2
        const val MAX_BLOCKS = 200
    }
}

// ───────────────────────── Qué leer ─────────────────────────

/** Un objeto COSEM a leer. [channelAware]: probar B=1 si B=0 no existe (contadores con varios contratos). */
class DlmsObject(
    val code: String,
    val classId: Int,
    val a: Int,
    val c: Int,
    val d: Int,
    val e: Int,
    val kind: Kind,
    val channelAware: Boolean = false,
    /** Canal B exacto (objetos descubiertos en el contador). Si es null se usa 0 o el canal sondeado. */
    val b: Int? = null,
) {
    enum class Kind { SCALED, TEXT, CLOCK }

    fun obisWith(channel: Int): ByteArray = Dlms.obis(a, b ?: if (a == 1) channel else 0, c, d, e)
}

object DlmsCatalog {
    private fun reg(c: Int, d: Int, e: Int, cls: Int = Dlms.CLASS_REGISTER) =
        DlmsObject("$c.$d.$e", cls, 1, c, d, e, DlmsObject.Kind.SCALED, channelAware = true)

    val identity: List<DlmsObject> = listOf(
        DlmsObject("96.1.1", Dlms.CLASS_DATA, 0, 96, 1, 1, DlmsObject.Kind.TEXT),
        DlmsObject("42.0.0", Dlms.CLASS_DATA, 0, 42, 0, 0, DlmsObject.Kind.TEXT),
        DlmsObject("0.9.1", Dlms.CLASS_CLOCK, 0, 1, 0, 0, DlmsObject.Kind.CLOCK),
    )

    private val imported = (0..6).map { reg(1, 8, it) }
    private val exported = (0..6).map { reg(2, 8, it) }
    private val reactive = listOf(3, 4, 5, 6, 7, 8).map { reg(it, 8, 0) }
    private val instantaneous = listOf(
        reg(1, 7, 0), reg(2, 7, 0), reg(3, 7, 0), reg(4, 7, 0),
        reg(21, 7, 0), reg(41, 7, 0), reg(61, 7, 0),
        reg(32, 7, 0), reg(52, 7, 0), reg(72, 7, 0),
        reg(31, 7, 0), reg(51, 7, 0), reg(71, 7, 0),
        reg(14, 7, 0), reg(13, 7, 0),
    )
    private val maxDemand =
        (0..6).map { reg(1, 6, it, Dlms.CLASS_EXT_REGISTER) } + (0..6).map { reg(2, 6, it, Dlms.CLASS_EXT_REGISTER) }

    fun forMode(mode: ReadMode): List<DlmsObject> = when (mode) {
        ReadMode.CONSUMO -> identity + imported + reg(3, 8, 0) + instantaneous
        ReadMode.FV -> identity + exported + reg(4, 8, 0) + instantaneous
        ReadMode.TODO -> identity + imported + exported + reactive + instantaneous + maxDemand
    }
}

/** Resultado de leer una lista de objetos. */
class DlmsReadResult(
    val values: List<ObisValue>,
    val scaledRead: Int,
    val denied: Int,
    val missing: Int,
    val failed: List<String>,
)

object DlmsReader {
    /**
     * Lee [specs] por GET. Un objeto que el contador no entrega no detiene la lectura; solo un fallo de
     * comunicación lanza excepción. [cache] guarda escalador y unidad (no cambian entre lecturas).
     */
    fun read(
        client: DlmsClient,
        specs: List<DlmsObject>,
        cache: MutableMap<String, Pair<Int, Int>>,
        status: (String) -> Unit = {},
        unavailable: MutableMap<String, Int> = HashMap(),
    ): DlmsReadResult {
        val values = mutableListOf<ObisValue>()
        var scaledRead = 0
        var denied = 0
        var missing = 0
        val failed = mutableListOf<String>()
        var channel = 0
        var probed = false

        for ((i, spec) in specs.withIndex()) {
            // Lo que el contador ya negó o no tiene no se vuelve a pedir: acelera las lecturas siguientes.
            val ukey = "${spec.classId}:${spec.code}:${spec.b ?: -1}"
            when (unavailable[ukey]) {
                1 -> { denied++; continue }
                2 -> { missing++; continue }
            }
            status("Leyendo ${spec.code} (${i + 1}/${specs.size})…")
            var result = client.get(spec.classId, spec.obisWith(channel), 2)
            if (spec.channelAware && !probed) {
                probed = true
                if (result is GetResult.Failure && Dlms.isMissing(result.code)) {
                    val alt = client.get(spec.classId, spec.obisWith(1), 2)
                    if (alt is GetResult.Value) {
                        channel = 1
                        result = alt
                    }
                }
            }
            if (result is GetResult.Failure) {
                when {
                    Dlms.isDenied(result.code) -> { denied++; unavailable[ukey] = 1 }
                    Dlms.isMissing(result.code) -> { missing++; unavailable[ukey] = 2 }
                    else -> failed += "${spec.code}: ${result.text}"
                }
                continue
            }
            val data = (result as? GetResult.Value)?.data ?: continue

            when (spec.kind) {
                DlmsObject.Kind.TEXT -> Dlms.plainText(data)?.takeIf { it.isNotEmpty() }
                    ?.let { values += ObisValue(spec.code, it, null) }
                DlmsObject.Kind.CLOCK -> (data as? DlmsData.Bytes)?.let { Dlms.clockText(it.value) }?.let { (date, time) ->
                    values += ObisValue("0.9.2", date, null)
                    values += ObisValue("0.9.1", time, null)
                }
                DlmsObject.Kind.SCALED -> {
                    val key = "$channel:${spec.code}"
                    val su = cache[key] ?: run {
                        val s = client.get(spec.classId, spec.obisWith(channel), 3)
                        (s as? GetResult.Value)?.let { Dlms.parseScalerUnit(it.data) }?.also { cache[key] = it }
                    } ?: (0 to 255)
                    val text = Dlms.scaledText(data, su.first)
                    if (text != null) {
                        values += ObisValue(spec.code, text, Dlms.unitText(su.second).ifEmpty { null })
                        scaledRead++
                    }
                }
            }
        }
        return DlmsReadResult(values, scaledRead, denied, missing, failed)
    }
}

/** Parámetros del arranque de la comunicación. */
object DlmsSetup {
    /** Direcciones de servidor a probar: la configurada, o las habituales si es automática. */
    fun serverCandidates(auto: Boolean, logical: Int, physical: Int): List<HdlcAddress> =
        if (auto) {
            listOf(HdlcAddress(1, 16), HdlcAddress(1), HdlcAddress(1, 1))
        } else {
            listOf(if (physical <= 0) HdlcAddress(logical) else HdlcAddress(logical, physical))
        }

    /** ACK del modo E (HDLC): 06 '2' Z '2' CR LF. */
    fun modeEAck(baudChar: Char): ByteArray =
        byteArrayOf(0x06, '2'.code.toByte(), baudChar.code.toByte(), '2'.code.toByte(), 0x0D, 0x0A)
}
