package es.lectorcontador

data class Identification(val manufacturer: String, val baudChar: Char, val text: String)

sealed interface FrameResult {
    data class Ok(val payload: String) : FrameResult
    data class BadChecksum(val payload: String) : FrameResult
}

/**
 * Protocolo IEC 62056-21 (antes IEC 1107), modo C, lectura de datos (readout).
 *
 *  1. Maestro -> "/?!\r\n"                        a 300 baud, 7E1
 *  2. Contador -> "/XXXZ<ident>\r\n"              Z = velocidad propuesta
 *  3. Maestro -> ACK '0' Z '0' \r\n               '0' = lectura de datos
 *  4. Ambos cambian a la velocidad Z
 *  5. Contador -> STX <líneas OBIS> '!' \r\n ETX BCC
 *
 * Esta clase no depende de Android para poder probarla con JUnit en el PC.
 */
object Iec62056 {
    const val STX: Byte = 0x02
    const val ETX: Byte = 0x03
    const val SOH: Byte = 0x01
    const val ACK: Byte = 0x06
    const val INITIAL_BAUD = 300

    val REQUEST: ByteArray = "/?!\r\n".toByteArray(Charsets.US_ASCII)

    fun baudFor(c: Char): Int? = when (c) {
        '0' -> 300
        '1' -> 600
        '2' -> 1200
        '3' -> 2400
        '4' -> 4800
        '5' -> 9600
        '6' -> 19200
        else -> null
    }

    /** ACK + control de protocolo '0' + velocidad + control de modo ('0' = lectura de datos) + CRLF. */
    fun ackReadout(baudChar: Char): ByteArray =
        byteArrayOf(ACK, '0'.code.toByte(), baudChar.code.toByte(), '0'.code.toByte(), 0x0D, 0x0A)

    /** Trama de fin de sesión: SOH 'B' '0' ETX BCC. */
    fun breakFrame(): ByteArray {
        val body = byteArrayOf('B'.code.toByte(), '0'.code.toByte(), ETX)
        var bcc = 0
        body.forEach { bcc = bcc xor it.toInt() }
        return byteArrayOf(SOH) + body + byteArrayOf(bcc.toByte())
    }

    /** Interpreta "/XXXZ<ident>" (la línea de identificación del contador). */
    fun parseIdentification(line: String): Identification? {
        val s = line.trim()
        val i = s.indexOf('/')
        if (i < 0) return null
        val t = s.substring(i + 1)
        if (t.length < 4) return null
        if (!t.take(3).all { it.isLetter() }) return null
        val z = t[3]
        if (baudFor(z) == null) return null
        var rest = t.substring(4)
        // Identificación ampliada: "\2" (o similar) tras el carácter de velocidad
        if (rest.startsWith("\\") && rest.length >= 2) rest = rest.substring(2)
        return Identification(t.take(3), z, rest)
    }

    /**
     * Busca la identificación en el texto recibido, ignorando el eco de nuestra propia
     * petición ("/?!") que producen algunas sondas. Solo se consideran líneas completas.
     */
    fun findIdentification(text: String): Identification? {
        val complete = text.substringBeforeLast('\n', missingDelimiterValue = "")
        return complete.split('\n').mapNotNull { parseIdentification(it) }.lastOrNull()
    }

    /**
     * Extrae el bloque de datos STX ... ETX BCC. Devuelve null si la trama aún no está completa.
     * El BCC es el XOR de todos los bytes tras STX hasta ETX incluido.
     */
    fun extractFrame(bytes: ByteArray): FrameResult? {
        val stx = bytes.indexOf(STX)
        if (stx < 0) return null
        var etx = -1
        for (i in stx + 1 until bytes.size) {
            if (bytes[i] == ETX) { etx = i; break }
        }
        if (etx < 0 || etx + 1 >= bytes.size) return null

        var bcc = 0
        for (i in stx + 1..etx) bcc = bcc xor (bytes[i].toInt() and 0x7F)
        val expected = bytes[etx + 1].toInt() and 0x7F

        val payload = String(
            ByteArray(etx - stx - 1) { (bytes[stx + 1 + it].toInt() and 0x7F).toByte() },
            Charsets.US_ASCII,
        )
        return if (bcc == expected) FrameResult.Ok(payload) else FrameResult.BadChecksum(payload)
    }

    private val CODE_REGEX = Regex("""^(?:\d+-\d+:)?([0-9A-Za-z.]+)(?:\*\d+)?$""")
    private val GROUP_REGEX = Regex("""\(([^)]*)\)""")

    /** Convierte las líneas OBIS "1-0:1.8.0(001234.567*kWh)" en una lista de valores. */
    fun parseDataBlock(text: String): List<ObisValue> {
        val out = mutableListOf<ObisValue>()
        for (rawLine in text.split('\n')) {
            val line = rawLine.trim()
            if (line.isEmpty() || line == "!") continue
            val open = line.indexOf('(')
            if (open <= 0) continue
            val rawCode = line.substring(0, open).trim()
            val code = CODE_REGEX.find(rawCode)?.groupValues?.get(1) ?: rawCode
            // Todos los paréntesis de la línea: el primero es el valor y los demás, datos extra (fechas...).
            val groups = GROUP_REGEX.findAll(line.substring(open)).map { it.groupValues[1] }.toList()
            val first = groups.firstOrNull() ?: continue
            val star = first.indexOf('*')
            val value = if (star >= 0) first.substring(0, star) else first
            val unit = if (star >= 0) first.substring(star + 1).ifEmpty { null } else null
            out += ObisValue(code, value, unit, groups.drop(1))
        }
        return out
    }
}
