package es.lectorcontador

import android.content.Context
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Histórico de lecturas de un contador en un CSV interno de la app (una línea por lectura).
 * Cada contador tiene el suyo: mezclar totales de contadores distintos falsearía los consumos diarios.
 * Se exporta como libro de Excel con el formato del informe «Sant Llorenç» (ver [ExcelReport]).
 */
class ReadingStore(context: Context, private val meter: MeterProfile) {
    private fun fileName(base: String, ext: String) = "${base}_${meter.id}.$ext"

    private val file = File(context.filesDir, fileName("lecturas", "csv"))
    private val obisFile = File(context.filesDir, fileName("obis_completo", "tsv"))
    private val exportDir = File(context.cacheDir, "exports")

    @Synchronized
    fun load(): List<StoredReading> {
        if (!file.exists()) return emptyList()
        return file.readLines().mapNotNull(ReadingCsv::parse).sortedBy { it.epochMs }
    }

    @Synchronized
    fun append(r: StoredReading) {
        file.appendText(ReadingCsv.format(r) + "\n")
    }

    @Synchronized
    fun appendObis(epochMs: Long, values: List<ObisValue>) {
        if (values.isEmpty()) return
        obisFile.appendText(values.joinToString("") { ObisLog.format(epochMs, it) + "\n" })
    }

    @Synchronized
    fun clear() {
        file.delete()
        obisFile.delete()
    }

    /** Archivos a compartir: un libro de Excel con el resumen, los cierres mensuales y los códigos OBIS. */
    @Synchronized
    fun exportAll(location: String): List<File> = listOfNotNull(exportExcel(location))

    /** Genera el .xlsx en la caché de la app. Devuelve null si no hay lecturas. */
    @Synchronized
    fun exportExcel(location: String): File? {
        val data = load()
        if (data.isEmpty()) return null
        val obisRows = if (obisFile.exists()) obisFile.readLines().mapNotNull(ObisLog::parse) else emptyList()
        val bytes = ExcelReport.build(data, location.ifBlank { "—" }, meter.obisFamily, obisRows) ?: return null
        exportDir.mkdirs()
        val stamp = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss").withZone(ZoneId.systemDefault()).format(Instant.now())
        val out = File(exportDir, "lecturas_${meter.id}_$stamp.xlsx")
        out.writeBytes(bytes)
        return out
    }
}
