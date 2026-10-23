package es.lectorcontador

/** Qué se quiere ver y guardar en el detalle de cada lectura. */
enum class ReadMode(val label: String) {
    CONSUMO("Consumo"),
    FV("FV"),
    TODO("Todo");

    fun includes(group: ObisGroup): Boolean = when (this) {
        TODO -> true
        CONSUMO -> group == ObisGroup.CONSUMO || group == ObisGroup.INSTANTANEO || group == ObisGroup.GENERAL
        FV -> group == ObisGroup.FV || group == ObisGroup.INSTANTANEO || group == ObisGroup.GENERAL
    }
}

/** Grupo al que pertenece un registro OBIS, según su primer número (magnitud). */
enum class ObisGroup(val label: String) {
    CONSUMO("consumo"),            // 1.x.x activa importada, 3.x.x reactiva importada
    FV("fotovoltaica"),            // 2.x.x activa exportada, 4.x.x reactiva exportada
    INSTANTANEO("instantáneo"),    // tensión, intensidad, factor de potencia, frecuencia
    GENERAL("general"),            // 0.x.x identificadores, fecha y hora; 96.x.x, 97.x.x...
    OTROS("otros"),
}

/** Clasificación y descripciones en castellano de los códigos OBIS más habituales. */
object ObisInfo {
    private val INSTANT = setOf(11, 12, 13, 14, 31, 32, 51, 52, 71, 72)

    fun groupOf(code: String): ObisGroup {
        val a = code.substringBefore('.').toIntOrNull() ?: return ObisGroup.OTROS
        return when (a) {
            0, 96, 97, 98, 99 -> ObisGroup.GENERAL
            1, 3 -> ObisGroup.CONSUMO
            2, 4 -> ObisGroup.FV
            in INSTANT -> ObisGroup.INSTANTANEO
            else -> ObisGroup.OTROS
        }
    }

    private val FIXED = mapOf(
        "0.0.0" to "Identificador del contador",
        "0.0.1" to "Identificador del contador",
        "96.1.1" to "Nº de serie",
        "0.9.1" to "Hora",
        "0.9.2" to "Fecha",
        "5.8.0" to "Energía reactiva cuadrante Q1",
        "6.8.0" to "Energía reactiva cuadrante Q2",
        "7.8.0" to "Energía reactiva cuadrante Q3",
        "8.8.0" to "Energía reactiva cuadrante Q4",
        "13.7.0" to "Factor de potencia",
        "14.7.0" to "Frecuencia",
        "32.7.0" to "Tensión L1",
        "52.7.0" to "Tensión L2",
        "72.7.0" to "Tensión L3",
        "31.7.0" to "Intensidad L1",
        "51.7.0" to "Intensidad L2",
        "71.7.0" to "Intensidad L3",
    )

    /** Descripción en castellano, o null si el código no es de los conocidos. */
    fun label(code: String): String? {
        FIXED[code]?.let { return it }
        val p = code.split('.')
        if (p.size != 3) return null
        val kind = when (p[0]) {
            "1" -> "activa importada"
            "2" -> "activa exportada"
            "3" -> "reactiva importada"
            "4" -> "reactiva exportada"
            else -> return null
        }
        val n = p[2].toIntOrNull() ?: return null
        return when (p[1]) {
            "8" -> if (n == 0) "Energía $kind total" else "Energía $kind P$n"
            "7" -> if (n == 0) "Potencia $kind" else null
            "6" -> if (n == 0) "Máxima demanda $kind" else "Máxima demanda $kind P$n"
            else -> null
        }
    }
}
