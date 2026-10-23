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
        fun encodeField(value: Int): ByteArray {
            require(value in 0..16383) { "dirección HDLC fuera de rango (0–16383): $value" }
            return if (value <= 127) {
                byteArrayOf(((value shl 1) or 1).toByte())
            } else {
                // Direccionamiento extendido de 2 octetos
                val b1 = (value shr 7) and 0xFE
                val b2 = ((value shl 1) and 0xFE) or 1
                byteArrayOf(b1.toByte(), b2.toByte())
            }
        }

        val uBytes = encodeField(upper)
        return if (lower == null) {
            uBytes
        } else {
            // Si hay dirección física, el último byte de la parte lógica lleva el bit 0 a 0
            uBytes[uBytes.size - 1] = (uBytes[uBytes.size - 1].toInt() and 0xFE).toByte()
            uBytes + encodeField(lower)
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
        val frames = mutableListOf<HdlcFrame>()
        val flag = FLAG.toByte()
        var i = 0
        while (true) {
            var start = i
            while (start < buf.size && buf[start] != flag) start++
            if (start >= buf.size) return Extracted(frames, ByteArray(0))
            if (start + 2 >= buf.size) return Extracted(frames, buf.copyOfRange(start, buf.size))
            val b0 = buf[start + 1].toInt() and 0xFF
            if ((b0 and 0xF0) != 0xA0) {     // bandera repetida o basura
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
        override fun toString
