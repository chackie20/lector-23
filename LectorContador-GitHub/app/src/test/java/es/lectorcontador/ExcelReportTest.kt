package es.lectorcontador

import java.io.ByteArrayInputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcelReportTest {
    private val zone = ZoneId.of("Europe/Madrid")

    private fun at(m: Int, d: Int, active: Double, reactive: Double = 10.0) = StoredReading(
        epochMs = LocalDateTime.of(2026, m, d, 8, 0).atZone(zone).toInstant().toEpochMilli(),
        totalKwh = null, periodsKwh = List(PERIODS) { active + it },
        powerKw = null, voltageV = null, currentA = null,
        reactivePeriodsKvarh = List(PERIODS) { reactive + it },
    )

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                out[e.name] = String(z.readBytes(), Charsets.UTF_8)
            }
        }
        return out
    }

    private fun sample(): ByteArray = ExcelReport.build(
        listOf(at(3, 3, 100.0), at(3, 20, 150.0), at(4, 2, 200.0), at(5, 5, 300.0)),
        "Sant Llorenç", ObisFamily.ENEL, zone = zone,
    )!!

    @Test fun no_readings_gives_null() {
        assertNull(ExcelReport.build(emptyList(), "X", ObisFamily.STANDARD, zone = zone))
    }

    @Test fun every_part_is_well_formed_xml() {
        val parts = unzip(sample())
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        for ((name, xml) in parts) {
            assertNotNull(name, factory.newDocumentBuilder().parse(ByteArrayInputStream(xml.toByteArray())))
        }
    }

    @Test fun sheets_follow_the_reference_layout() {
        val parts = unzip(sample())
        val wb = parts.getValue("xl/workbook.xml")
        val order = listOf(
            "Resum Executiu", "Lectures Actuals",
            "Tancament 01 - Maig", "Tancament 02 - Abril", "Tancament 03 - Març",
        )
        var last = -1
        for (n in order) {
            val i = wb.indexOf("name=\"$n\"")
            assertTrue("falta la hoja $n", i > last)
            last = i
        }
        assertTrue(parts.containsKey("xl/charts/chart1.xml"))
        assertTrue(parts.containsKey("xl/drawings/drawing1.xml"))
    }

    @Test fun closure_is_first_reading_of_month_and_uses_enel_codes() {
        val parts = unzip(sample())
        val march = parts.getValue("xl/worksheets/sheet5.xml") // Tancament 03 - Març
        assertTrue(march.contains("Tancament del 03/03/2026"))
        assertTrue(march.contains("1.18.1") && march.contains("1.58.6") && march.contains("1.18.0"))
        // P1..P6 = 100..105 → suma 615 (la lectura del día 20 no es el cierre)
        assertTrue(march.contains("<f>SUM(D7:D12)</f><v>615.0</v>"))
        assertTrue(march.contains("<v>100.0</v>"))
    }

    @Test fun summary_has_formulas_and_increments() {
        val rs = unzip(sample()).getValue("xl/worksheets/sheet1.xml")
        assertTrue(rs.contains("<f>'Tancament 03 - Març'!D13</f>"))
        assertTrue(rs.contains("<f>'Lectures Actuals'!D23</f>"))
        assertTrue(rs.contains("Març -&gt; Abril 2026") || rs.contains("Març -> Abril 2026"))
        assertTrue(rs.contains("<f>D7-D6</f>"))
        assertTrue(rs.contains("INFORME DE CONSUMS ENERGÈTICS - SANT LLORENÇ"))
    }

    @Test fun obis_sheet_only_when_rows_given() {
        val readings = listOf(at(3, 3, 100.0))
        val without = unzip(ExcelReport.build(readings, "X", ObisFamily.STANDARD, zone = zone)!!)
        assertTrue(!without.getValue("xl/workbook.xml").contains("OBIS complet"))
        val rows = listOf(readings[0].epochMs to ObisValue("1.8.0", "123.4", "kWh"))
        val with = unzip(ExcelReport.build(readings, "X", ObisFamily.STANDARD, rows, zone)!!)
        assertTrue(with.getValue("xl/workbook.xml").contains("OBIS complet"))
    }

    @Test fun helpers() {
        assertEquals("A", colLetter(1))
        assertEquals("AA", colLetter(27))
        assertEquals("a &amp; b &lt;c&gt;", xmlEscape("a & b <c>"))
        assertEquals("1234567.5", xmlNum(1234567.5))
        assertEquals("12000000", xmlNum(1.2e7).substringBefore('.'))
        assertEquals("Tancament 01 - Setembre".take(31), XlsxWriter.sheetName("Tancament 01 - Setembre"))
        assertEquals(31, XlsxWriter.sheetName("x".repeat(50)).length)
        assertEquals("a b", XlsxWriter.sheetName("a/b"))
    }

    @Test fun csv_v4_keeps_reactive_and_old_lines_still_parse() {
        val s = at(3, 3, 100.0, 7.0)
        val back = ReadingCsv.parse(ReadingCsv.format(s))!!
        assertEquals(s.reactivePeriodsKvarh, back.reactivePeriodsKvarh)
        val v3 = ReadingCsv.format(s).split(',').take(19).joinToString(",")
        val old = ReadingCsv.parse(v3)!!
        assertTrue(old.reactivePeriodsKvarh.all { it == null })
        assertEquals(s.periodsKwh, old.periodsKwh)
    }

    @Test fun enel_style_codes_are_understood() {
        val values = (1..6).map { ObisValue("1.18.$it", "10$it", "kWh") } +
            (1..6).map { ObisValue("1.58.$it", "5$it", "kvarh") }
        val r = MeterReading(0, "ENEL", values, "")
        assertEquals(101.0, r.periodsKwh[0]!!, 1e-9)
        assertEquals(51.0, r.reactivePeriodsKvarh[0]!!, 1e-9)
        assertEquals(10.0 * 0 + 101 + 102 + 103 + 104 + 105 + 106, r.totalKwh!!, 1e-9)
    }
}
