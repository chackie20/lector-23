package es.lectorcontador

/**
 * Descubrimiento de objetos: la asociación COSEM (0-0:40.0.0.255, clase 15) guarda en su atributo 2
 * la lista de objetos que el contador entrega al cliente conectado. Sin dependencias de Android.
 */
data class CosemObject(
    val classId: Int,
    val version: Int,
    val a: Int,
    val b: Int,
    val c: Int,
    val d: Int,
    val e: Int,
    val f: Int,
) {
    /** Código OBIS completo, p. ej. "1-0:1.8.0.255". */
    val obis: String get() = "$a-$b:$c.$d.$e.$f"

    /** Código corto que usa la app para los valores, p. ej. "1.8.0"; completo si el canal no es 0 o 1. */
    val code: String
        get() = if (a <= 1 && b <= 1) "$c.$d.$e" else "$a-$b:$c.$d.$e"
}

object DlmsDiscovery {
    /** Objeto "lista de asociación" (LN) del contador. */
    val ASSOCIATION_OBIS: ByteArray = Dlms.obis(0, 0, 40, 0, 0)

    /** Clases que se pueden leer como un valor con escalador: registro y registro extendido. */
    private val READABLE_CLASSES = setOf(Dlms.CLASS_REGISTER, Dlms.CLASS_EXT_REGISTER)

    /** Interpreta el atributo 2 de la asociación: array de {clase, versión, nombre lógico, derechos}. */
    fun parseObjectList(data: DlmsData): List<CosemObject> {
        val entries = (data as? DlmsData.Items)?.items ?: return emptyList()
        val out = ArrayList<CosemObject>(entries.size)
        for (entry in entries) {
            val s = (entry as? DlmsData.Items)?.items ?: continue
            if (s.size < 3) continue
            val cls = (s[0] as? DlmsData.Num)?.value?.toInt() ?: continue
            val ver = (s[1] as? DlmsData.Num)?.value?.toInt() ?: 0
            val ln = (s[2] as? DlmsData.Bytes)?.value ?: continue
            if (ln.size != 6) continue
            fun u(i: Int) = ln[i].toInt() and 0xFF
            out += CosemObject(cls, ver, u(0), u(1), u(2), u(3), u(4), u(5))
        }
        return out
    }

    /**
     * Registros del contador que el catálogo estándar [known] no incluye ya (máximo [limit]),
     * para que el modo "Todo" lea también los códigos propios de cada fabricante.
     */
    fun extraSpecs(found: List<CosemObject>, known: List<DlmsObject>, limit: Int = 150): List<DlmsObject> {
        val knownKeys = known.map { "${it.a}:${it.c}.${it.d}.${it.e}" }.toHashSet()
        val seen = HashSet<String>()
        val out = ArrayList<DlmsObject>()
        for (o in found) {
            if (o.classId !in READABLE_CLASSES) continue
            val key = "${o.a}:${o.c}.${o.d}.${o.e}"
            val standardChannel = o.a <= 1 && o.b <= 1
            if (standardChannel && key in knownKeys) continue
            if (!seen.add("${o.a}-${o.b}:${o.c}.${o.d}.${o.e}")) continue
            out += DlmsObject(
                code = o.code,
                classId = o.classId,
                a = o.a,
                c = o.c,
                d = o.d,
                e = o.e,
                kind = DlmsObject.Kind.SCALED,
                channelAware = false,
                b = o.b,
            )
            if (out.size >= limit) break
        }
        return out
    }
}
