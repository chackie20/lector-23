package es.lectorcontador

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Consumo por día a partir de lecturas consecutivas del contador.
 * La diferencia entre dos lecturas se asigna al día de la lectura más reciente.
 * Las diferencias negativas (contador sustituido o reiniciado) se ignoran.
 */
fun dailyConsumption(
    history: List<StoredReading>,
    zone: ZoneId = ZoneId.systemDefault(),
    maxDays: Int = 14,
): List<Pair<LocalDate, Double>> {
    val sorted = history.filter { it.totalKwh != null }.sortedBy { it.epochMs }
    val perDay = sortedMapOf<LocalDate, Double>()
    for (i in 1 until sorted.size) {
        val delta = sorted[i].totalKwh!! - sorted[i - 1].totalKwh!!
        if (delta < 0) continue
        val day = Instant.ofEpochMilli(sorted[i].epochMs).atZone(zone).toLocalDate()
        perDay[day] = (perDay[day] ?: 0.0) + delta
    }
    return perDay.entries.map { it.key to it.value }.takeLast(maxDays)
}
