package es.lectorcontador

/** Formatos legibles de bytes recibidos (sin dependencias de Android). */
object ByteFormat {
    /** Texto ASCII; CR/LF se muestran como \r y \n, y lo no imprimible como '.'. */
    fun ascii(bytes: ByteArray, max: Int = 120): String {
        val sb = StringBuilder()
        for (b in bytes.take(max)) {
            when (val v = b.toInt() and 0xFF) {
                0x0D -> sb.append("\\r")
                0x0A -> sb.append("\\n")
                in 0x20..0x7E -> sb.append(v.toChar())
                else -> sb.append('.')
            }
        }
        if (bytes.size > max) sb.append("…")
        return sb.toString()
    }

    fun hex(bytes: ByteArray, max: Int = 64): String {
        val s = bytes.take(max).joinToString(" ") { "%02X".format(it.toInt() and 0xFF) }
        return if (bytes.size > max) "$s …" else s
    }
}
