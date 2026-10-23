package es.lectorcontador

import java.util.Locale

/**
 * Formato de línea del histórico interno (sin dependencias de Android, para poder probarlo).
 *
 *  - v4 (25 campos): los de v3 + reactiva importada R1..R6
 *  - v3 (19 campos): epochMs, total, P1..P6, potencia, tensión, intensidad,
 *                    exportado total, E1..E6, potencia exportada
 *  - v2 (11 campos): epochMs, total, P1..P6, potencia, tensión, intensidad     (se sigue leyendo)
 *  - v1 (8 campos):  epochMs, total, P1..P3, potencia, tensión, intensidad     (se sigue leyendo)
 */
object ReadingCsv {
    private const val FIELDS_V4 = 25
    private const val FIELDS_V3 = 19
    private const val FIELDS_V2 = 11
    private const val FIELDS_V1 = 8

    fun format(r: StoredReading): String {
        fun n(v: Double?) = v?.let { String.format(Locale.US, "%.4f", it) } ?: ""
        val periods = (0 until PERIODS).map { n(r.periodsKwh.getOrNull(it)) }
        val exported = (0 until PERIODS).map { n(r.exportPeriodsKwh.getOrNull(it)) }
        return (
            listOf(r.epochMs.toString(), n(r.totalKwh)) + periods +
                listOf(n(r.powerKw), n(r.voltageV), n(r.currentA), n(r.exportKwh)) +
                exported + listOf(n(r.exportPowerKw)) +
                (0 until PERIODS).map { n(r.reactivePeriodsKvarh.getOrNull(it)) }
            ).joinToString(",")
    }

    fun parse(line: String): StoredReading? {
        val p = line.trim().split(',')
        val t = p.firstOrNull()?.toLongOrNull() ?: return null
        fun d(i: Int) = p.getOrNull(i)?.toDoubleOrNull()
        val periods = (0 until PERIODS).map { d(2 + it) }
        return when {
            p.size >= FIELDS_V4 -> StoredReading(
                t, d(1), periods, d(8), d(9), d(10),
                d(11), (0 until PERIODS).map { d(12 + it) }, d(18),
                (0 until PERIODS).map { d(19 + it) },
            )
            p.size >= FIELDS_V3 -> StoredReading(
                t, d(1), periods, d(8), d(9), d(10),
                d(11), (0 until PERIODS).map { d(12 + it) }, d(18),
            )
            p.size == FIELDS_V2 -> StoredReading(t, d(1), periods, d(8), d(9), d(10))
            p.size == FIELDS_V1 -> StoredReading(
                t, d(1), listOf(d(2), d(3), d(4), null, null, null), d(5), d(6), d(7),
            )
            else -> null
        }
    }
}

/**
 * Registro de todos los códigos OBIS de cada lectura: una línea por código, separada por tabuladores.
 * Formato: epochMs \t código \t valor \t unidad \t extras (separados por '|').
 */
object ObisLog {
    private fun clean(s: String) = s.replace('\t', ' ').replace('\r', ' ').replace('\n', ' ')

    fun format(epochMs: Long, v: ObisValue): String =
        listOf(
            epochMs.toString(), clean(v.code), clean(v.value), clean(v.unit.orEmpty()),
            v.extras.joinToString("|") { clean(it).replace('|', '/') },
        ).joinToString("\t")

    fun parse(line: String): Pair<Long, ObisValue>? {
        val p = line.split('\t')
        if (p.size != 5) return null
        val t = p[0].toLongOrNull() ?: return null
        val extras = if (p[4].isEmpty()) emptyList() else p[4].split('|')
        return t to ObisValue(p[1], p[2], p[3].ifEmpty { null }, extras)
    }
}
