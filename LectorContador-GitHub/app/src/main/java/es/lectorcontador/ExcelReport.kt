package es.lectorcontador

import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.sqrt

/**
 * Familia de códigos OBIS que se muestra en las columnas «Codi OBIS» del Excel:
 * 1.8.x / 3.8.x (estándar, p. ej. ZIV) o 1.18.x / 1.58.x (pantalla de los Enel).
 */
class ObisFamily(val active: String, val reactive: String) {
    companion object {
        val STANDARD = ObisFamily("1.8", "3.8")
        val ENEL = ObisFamily("1.18", "1.58")
    }
}

/**
 * Informe en Excel con el formato del libro «Sant Llorenç»: hoja «Resum Executiu» (cierres mensuales,
 * incremento de consumo y gráfico), «Lectures Actuals» y una hoja «Tancament NN - Mes» por cada mes.
 * Un cierre es la primera lectura guardada de cada mes. Sin Android, para poder probarlo.
 */
object ExcelReport {
    private val MONTHS = listOf(
        "Gener", "Febrer", "Març", "Abril", "Maig", "Juny",
        "Juliol", "Agost", "Setembre", "Octubre", "Novembre", "Desembre",
    )
    const val MAX_CLOSURES = 12
    private const val MAX_OBIS_ROWS = 5000

    private class Closure(val ym: YearMonth, val date: String, val active: List<Double?>, val reactive: List<Double?>)

    private fun cos(a: Double, r: Double): Double {
        val d = a * a + r * r
        return if (d == 0.0) 0.0 else a / sqrt(d)
    }

    private fun padded(v: List<Double?>): List<Double?> = (0 until PERIODS).map { v.getOrNull(it) }

    /** Hoja de lecturas (la de «Lectures Actuals» y las de cada «Tancament»). Devuelve la hoja y sus totales. */
    private fun readingSheet(
        name: String, title: String, subtitle: String, dateLabel: String, dateValue: String,
        active: List<Double?>, reactive: List<Double?>, family: ObisFamily,
    ): Triple<XSheet, Double, Double> {
        val sh = XSheet(name)
        sh.widths[1] = 3.0; sh.widths[2] = 15.0; sh.widths[3] = 25.0; sh.widths[4] = 20.0
        sh.widths[5] = 4.0; sh.widths[6] = 26.0; sh.widths[7] = 18.0; sh.widths[8] = 10.0
        sh.str(2, 2, title, XS.TITLE)
        sh.str(3, 2, subtitle, XS.SUBTITLE)

        sh.str(5, 2, "ENERGIA ACTIVA", XS.SEC_BLUE); sh.blank(5, 3, XS.SEC_BLUE); sh.blank(5, 4, XS.SEC_BLUE)
        sh.merge(5, 2, 5, 4)
        sh.str(5, 6, "RESUM DEL TANCAMENT", XS.SEC_NAVY); sh.blank(5, 7, XS.SEC_NAVY); sh.blank(5, 8, XS.SEC_NAVY)
        sh.merge(5, 6, 5, 8)
        sh.str(6, 2, "Codi OBIS", XS.HEAD); sh.str(6, 3, "Període Tarifari", XS.HEAD); sh.str(6, 4, "Lectura (kWh)", XS.HEAD)
        for (i in 0 until PERIODS) {
            val r = 7 + i
            sh.str(r, 2, "${family.active}.${i + 1}", XS.CELL_C)
            sh.str(r, 3, "P${i + 1}", XS.CELL_C)
            val v = active[i]
            if (v == null) sh.blank(r, 4, XS.CELL_NUM) else sh.num(r, 4, v, XS.CELL_NUM)
        }
        val ta = active.filterNotNull().sum()
        sh.str(13, 2, "${family.active}.0", XS.TOT_C)
        sh.str(13, 3, "Total Energia Activa", XS.TOT_L)
        sh.formula(13, 4, "SUM(D7:D12)", XS.TOT_NUM, ta)

        sh.str(15, 2, "ENERGIA REACTIVA", XS.SEC_ORANGE); sh.blank(15, 3, XS.SEC_ORANGE); sh.blank(15, 4, XS.SEC_ORANGE)
        sh.merge(15, 2, 15, 4)
        sh.str(16, 2, "Codi OBIS", XS.HEAD); sh.str(16, 3, "Període Tarifari", XS.HEAD); sh.str(16, 4, "Lectura (kvarh)", XS.HEAD)
        for (i in 0 until PERIODS) {
            val r = 17 + i
            sh.str(r, 2, "${family.reactive}.${i + 1}", XS.CELL_C)
            sh.str(r, 3, "P${i + 1}", XS.CELL_C)
            val v = reactive[i]
            if (v == null) sh.blank(r, 4, XS.CELL_NUM) else sh.num(r, 4, v, XS.CELL_NUM)
        }
        val tr = reactive.filterNotNull().sum()
        sh.str(23, 2, "${family.reactive}.0", XS.TOT_C)
        sh.str(23, 3, "Total Energia Reactiva", XS.TOT_L)
        sh.formula(23, 4, "SUM(D17:D22)", XS.TOT_NUM, tr)

        sh.str(6, 6, dateLabel, XS.SUM_LABEL); sh.str(6, 7, dateValue, XS.SUM_TXT); sh.blank(6, 8, XS.SUM_UNIT)
        sh.str(7, 6, "Energia Activa Acumulada:", XS.SUM_LABEL)
        sh.formula(7, 7, "D13", XS.SUM_NUM, ta); sh.str(7, 8, "kWh", XS.SUM_UNIT)
        sh.str(8, 6, "Energia Reactiva Acumulada:", XS.SUM_LABEL)
        sh.formula(8, 7, "D23", XS.SUM_NUM, tr); sh.str(8, 8, "kvarh", XS.SUM_UNIT)
        sh.str(9, 6, "Factor de Potència (Cos φ):", XS.SUM_LABEL)
        sh.formula(9, 7, "IFERROR(D13/SQRT(D13^2 + D23^2),0)", XS.SUM_COS, cos(ta, tr)); sh.blank(9, 8, XS.SUM_UNIT)
        return Triple(sh, ta, tr)
    }

    private fun obisSheet(location: String, rows: List<Pair<Long, ObisValue>>, zone: ZoneId): XSheet {
        val sh = XSheet("OBIS complet")
        sh.widths[1] = 3.0; sh.widths[2] = 20.0; sh.widths[3] = 16.0; sh.widths[4] = 12.0
        sh.widths[5] = 44.0; sh.widths[6] = 18.0; sh.widths[7] = 10.0; sh.widths[8] = 34.0
        sh.str(2, 2, "CODIS OBIS - LECTURES GUARDADES", XS.TITLE)
        sh.str(3, 2, "Ubicació: $location | Tots els registres OBIS guardats en cada lectura", XS.SUBTITLE)
        val heads = listOf("Data i hora", "Grup", "Codi OBIS", "Descripció", "Valor", "Unitat", "Dades addicionals")
        heads.forEachIndexed { i, h -> sh.str(5, 2 + i, h, XS.HEAD) }
        val fmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss").withZone(zone)
        rows.takeLast(MAX_OBIS_ROWS).forEachIndexed { i, (t, v) ->
            val r = 6 + i
            sh.str(r, 2, fmt.format(Instant.ofEpochMilli(t)), XS.CELL_C)
            sh.str(r, 3, ObisInfo.groupOf(v.code).label, XS.CELL_L)
            sh.str(r, 4, v.code, XS.CELL_C)
            sh.str(r, 5, ObisInfo.label(v.code).orEmpty(), XS.CELL_L)
            val n = v.value.trim().replace(',', '.').toDoubleOrNull()
            if (n != null && n.isFinite()) sh.num(r, 6, n, XS.CELL_R) else sh.str(r, 6, v.value, XS.CELL_R)
            sh.str(r, 7, v.unit.orEmpty(), XS.CELL_C)
            sh.str(r, 8, v.extras.joinToString(" | "), XS.CELL_L)
        }
        return sh
    }

    /** @return el .xlsx, o null si no hay lecturas. */
    fun build(
        readings: List<StoredReading>,
        location: String,
        family: ObisFamily,
        obisRows: List<Pair<Long, ObisValue>> = emptyList(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): ByteArray? {
        if (readings.isEmpty()) return null
        val sorted = readings.sortedBy { it.epochMs }
        val current = sorted.last()
        val dayFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy").withZone(zone)
        val dateTimeFmt = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(zone)

        // Cierre de cada mes = primera lectura guardada de ese mes (los 12 últimos meses).
        val byMonth = sorted.groupBy { YearMonth.from(Instant.ofEpochMilli(it.epochMs).atZone(zone)) }
        val closures = byMonth.keys.sorted().takeLast(MAX_CLOSURES).map { ym ->
            val r = byMonth.getValue(ym).first()
            Closure(
                ym, dayFmt.format(Instant.ofEpochMilli(r.epochMs)),
                padded(r.periodsKwh), padded(r.reactivePeriodsKvarh),
            )
        }

        // Hojas de cierre: la más reciente es la 01 (como en el libro de referencia).
        val closureSheets = ArrayList<XSheet>()
        val totals = HashMap<Int, Pair<Double, Double>>() // índice cronológico -> (activa, reactiva)
        val sheetNames = ArrayList<String>()
        closures.forEachIndexed { chrono, c ->
            val k = closures.size - chrono
            val mes = MONTHS[c.ym.monthValue - 1]
            val nn = k.toString().padStart(2, '0')
            val name = XlsxWriter.sheetName("Tancament $nn - $mes")
            val (sh, ta, tr) = readingSheet(
                name, "LECTURES D'ENERGIA - TANCAMENT $nn - ${mes.uppercase()}",
                "Ubicació: $location | Estat: Tancament del ${c.date}",
                "Data de Tancament:", c.date, c.active, c.reactive, family,
            )
            closureSheets.add(sh)
            totals[chrono] = Pair(ta, tr)
            sheetNames.add(name)
        }
        closureSheets.reverse()

        val (cur, cta, ctr) = readingSheet(
            "Lectures Actuals", "LECTURES D'ENERGIA - LECTURES ACTUALS",
            "Ubicació: $location | Estat: Lectures actuals del comptador (${dateTimeFmt.format(Instant.ofEpochMilli(current.epochMs))})",
            "Data de Tancament:", "Lectures Actuals",
            padded(current.periodsKwh), padded(current.reactivePeriodsKvarh), family,
        )

        // Resumen ejecutivo
        val rs = XSheet("Resum Executiu")
        rs.widths[1] = 3.0; rs.widths[2] = 28.0; rs.widths[3] = 18.0; rs.widths[4] = 24.0
        rs.widths[5] = 24.0; rs.widths[6] = 25.0
        rs.str(2, 2, "INFORME DE CONSUMS ENERGÈTICS - ${location.uppercase()}", XS.TITLE)
        val range = if (closures.isNotEmpty()) {
            val a = closures.first().ym
            val b = closures.last().ym
            " (${MONTHS[a.monthValue - 1]} ${a.year} - ${MONTHS[b.monthValue - 1]} ${b.year})"
        } else ""
        rs.str(3, 2, "Resum Executiu i Històric de Tancaments de Lectura$range", XS.SUBTITLE)
        listOf(
            "Tancament", "Data Tancament", "Energia Activa (kWh)", "Energia Reactiva (kvarh)", "Factor de Potència (Cos φ)",
        ).forEachIndexed { i, h -> rs.str(5, 2 + i, h, XS.HEAD) }

        class Row(val name: String, val date: String, val ta: Double, val tr: Double)
        val rows = closures.indices.map { i ->
            Row(sheetNames[i], closures[i].date, totals.getValue(i).first, totals.getValue(i).second)
        } + Row("Lectures Actuals", "Actual", cta, ctr)
        rows.forEachIndexed { i, row ->
            val r = 6 + i
            val q = "'${row.name}'"
            rs.str(r, 2, row.name, XS.CELL_L)
            rs.str(r, 3, row.date, XS.CELL_C)
            rs.formula(r, 4, "$q!D13", XS.CELL_NUM, row.ta)
            rs.formula(r, 5, "$q!D23", XS.CELL_NUM, row.tr)
            rs.formula(r, 6, "IFERROR(D$r/SQRT(D$r^2 + E$r^2),0)", XS.CELL_COS, cos(row.ta, row.tr))
        }
        val lastRow = 5 + rows.size
        if (closures.size >= 2) {
            rs.str(lastRow + 2, 2, "INCREMENT DE CONSUM MENSUAL (KWH I KVARH)", XS.H2)
            val h = lastRow + 4
            rs.str(h, 2, "Període d'Increment", XS.HEAD_BLUE)
            rs.str(h, 3, "Consum Activa (kWh)", XS.HEAD_BLUE)
            rs.str(h, 4, "Consum Reactiva (kvarh)", XS.HEAD_BLUE)
            for (i in 1 until closures.size) {
                val p = closures[i - 1].ym
                val n = closures[i].ym
                val label = if (p.year == n.year) {
                    "${MONTHS[p.monthValue - 1]} -> ${MONTHS[n.monthValue - 1]} ${n.year}"
                } else {
                    "${MONTHS[p.monthValue - 1]} ${p.year} -> ${MONTHS[n.monthValue - 1]} ${n.year}"
                }
                val r = h + i
                val a = 5 + i
                val b = 6 + i
                rs.str(r, 2, label, XS.CELL_L)
                rs.formula(r, 3, "D$b-D$a", XS.CELL_NUM, rows[i].ta - rows[i - 1].ta)
                rs.formula(r, 4, "E$b-E$a", XS.CELL_NUM, rows[i].tr - rows[i - 1].tr)
            }
        }
        val chart = XlsxWriter.chartXml(
            "Evolució de l'Energia Activa Acumulada (kWh)", "Energia Activa (kWh)", rs.name, 6, lastRow,
            rows.map { it.name }, rows.map { it.ta },
        )

        val sheets = ArrayList<XSheet>()
        sheets.add(rs)
        sheets.add(cur)
        sheets.addAll(closureSheets)
        if (obisRows.isNotEmpty()) sheets.add(obisSheet(location, obisRows, zone))
        return XlsxWriter.write(sheets, chart)
    }
}
