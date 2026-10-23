package es.lectorcontador

import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Iec62056Test {

    private val sample = buildString {
        append("0.0.0(ES0012345678)\r\n")
        append("1-0:1.8.0(001234.567*kWh)\r\n")
        append("1-0:1.8.1(000400.100*kWh)\r\n")
        append("1-0:1.8.2(000500.200*kWh)\r\n")
        append("1-0:1.8.3(000334.267*kWh)\r\n")
        append("1-0:1.7.0(00.482*kW)\r\n")
        append("1-0:32.7.0(231.4*V)\r\n")
        append("1-0:31.7.0(002.10*A)\r\n")
        append("!\r\n")
    }

    private fun frame(payload: String, corruptBcc: Boolean = false): ByteArray {
        val body = (payload + "\u0003").toByteArray(Charsets.US_ASCII)
        var bcc = 0
        body.forEach { bcc = bcc xor it.toInt() }
        if (corruptBcc) bcc = bcc xor 0x01
        return byteArrayOf(0x02) + body + byteArrayOf(bcc.toByte())
    }

    @Test fun identification_basic() {
        val id = Iec62056.parseIdentification("/ORB5Orbis_Meter\r\n")
        assertNotNull(id)
        assertEquals("ORB", id!!.manufacturer)
        assertEquals(9600, Iec62056.baudFor(id.baudChar))
        assertEquals("Orbis_Meter", id.text)
    }

    @Test fun identification_enhanced() {
        val id = Iec62056.parseIdentification("/ZIV5\\2ZMD310")
        assertEquals("ZMD310", id!!.text)
    }

    @Test fun identification_ignores_echo_of_request() {
        val id = Iec62056.findIdentification("/?!\r\n/ZIV3Meter\r\n")
        assertEquals("ZIV", id!!.manufacturer)
        assertEquals(2400, Iec62056.baudFor(id.baudChar))
    }

    @Test fun identification_requires_complete_line() {
        assertNull(Iec62056.findIdentification("/ZIV3Met"))
        assertNull(Iec62056.findIdentification("/?!\r\n"))
    }

    @Test fun ack_bytes() {
        assertEquals(
            listOf<Byte>(0x06, 0x30, 0x35, 0x30, 0x0D, 0x0A),
            Iec62056.ackReadout('5').toList(),
        )
    }

    @Test fun break_frame_is_soh_B0_etx_q() {
        assertEquals(
            listOf<Byte>(0x01, 'B'.code.toByte(), '0'.code.toByte(), 0x03, 'q'.code.toByte()),
            Iec62056.breakFrame().toList(),
        )
    }

    @Test fun frame_ok_and_incomplete() {
        val bytes = frame(sample)
        assertTrue(Iec62056.extractFrame(bytes) is FrameResult.Ok)
        // sin el BCC final aún no está completa
        assertNull(Iec62056.extractFrame(bytes.copyOf(bytes.size - 1)))
        // sin ETX
        assertNull(Iec62056.extractFrame(bytes.copyOf(10)))
    }

    @Test fun frame_with_leading_garbage_and_echo() {
        val bytes = "xx\u0006050\r\n".toByteArray() + frame(sample)
        assertTrue(Iec62056.extractFrame(bytes) is FrameResult.Ok)
    }

    @Test fun frame_bad_checksum() {
        assertTrue(Iec62056.extractFrame(frame(sample, corruptBcc = true)) is FrameResult.BadChecksum)
    }

    @Test fun parse_data_block() {
        val values = Iec62056.parseDataBlock(sample)
        assertEquals(8, values.size)
        val total = values.first { it.code == "1.8.0" }
        assertEquals("001234.567", total.value)
        assertEquals("kWh", total.unit)
        assertEquals("ES0012345678", values.first { it.code == "0.0.0" }.value)
    }

    @Test fun reading_numbers() {
        val r = MeterReading(0, "ORB", Iec62056.parseDataBlock(sample), sample)
        assertEquals(1234.567, r.totalKwh!!, 1e-9)
        assertEquals(400.1, r.periodsKwh[0]!!, 1e-9)
        assertEquals(500.2, r.periodsKwh[1]!!, 1e-9)
        assertEquals(334.267, r.periodsKwh[2]!!, 1e-9)
        assertNull(r.periodsKwh[3])
        assertEquals(0.482, r.powerKw!!, 1e-9)
        assertEquals(231.4, r.voltageV!!, 1e-9)
        assertEquals(2.10, r.currentA!!, 1e-9)
        assertEquals("ES0012345678", r.serial)
    }

    @Test fun reading_converts_watts_and_sums_periods_when_no_total() {
        val raw = "1.8.1(1000*Wh)\r\n1.8.2(2000*Wh)\r\n1.7.0(480*W)\r\n!\r\n"
        val r = MeterReading(0, "X", Iec62056.parseDataBlock(raw), raw)
        assertEquals(3.0, r.totalKwh!!, 1e-9)
        assertEquals(0.48, r.powerKw!!, 1e-9)
    }

    @Test fun six_periods_and_total_from_sum() {
        val raw = (1..6).joinToString("") { "1-0:1.8.$it(${it * 100}.000*kWh)\r\n" } + "!\r\n"
        val r = MeterReading(0, "X", Iec62056.parseDataBlock(raw), raw)
        assertEquals(listOf(100.0, 200.0, 300.0, 400.0, 500.0, 600.0), r.periodsKwh.map { it!! })
        assertEquals(2100.0, r.totalKwh!!, 1e-9)
    }

    @Test fun csv_roundtrip_six_periods() {
        val s = StoredReading(1000L, 2100.0, listOf(100.0, 200.0, 300.0, 400.0, 500.0, null), 1.5, 230.0, 6.5)
        val back = ReadingCsv.parse(ReadingCsv.format(s))!!
        assertEquals(s, back)
        assertEquals(25, ReadingCsv.format(s).split(',').size)
    }

    @Test fun csv_roundtrip_with_fv_export() {
        val s = StoredReading(
            2000L, 10.5, listOf(1.0, 2.0, 3.0, null, null, null), 0.4, 231.0, 2.0,
            exportKwh = 55.25, exportPeriodsKwh = listOf(10.0, 20.0, 25.25, null, null, null), exportPowerKw = 1.75,
        )
        assertEquals(s, ReadingCsv.parse(ReadingCsv.format(s)))
    }

    @Test fun csv_reads_previous_eleven_field_lines() {
        val v2 = "1000,2100.0000,100.0000,200.0000,300.0000,400.0000,500.0000,600.0000,1.5000,230.0000,6.5000"
        val r = ReadingCsv.parse(v2)!!
        assertEquals(600.0, r.periodsKwh[5]!!, 1e-9)
        assertEquals(1.5, r.powerKw!!, 1e-9)
        assertNull(r.exportKwh)
    }

    @Test fun obis_keeps_extra_groups() {
        val raw = "1-0:1.6.1(02.5*kW)(2610011500)\r\n0.9.1(101530)\r\n!\r\n"
        val v = Iec62056.parseDataBlock(raw)
        assertEquals(listOf("2610011500"), v[0].extras)
        assertEquals("kW", v[0].unit)
        assertEquals("101530", v[1].value)
        assertTrue(v[1].extras.isEmpty())
    }

    @Test fun obis_groups_and_modes() {
        assertEquals(ObisGroup.CONSUMO, ObisInfo.groupOf("1.8.3"))
        assertEquals(ObisGroup.FV, ObisInfo.groupOf("2.8.0"))
        assertEquals(ObisGroup.INSTANTANEO, ObisInfo.groupOf("32.7.0"))
        assertEquals(ObisGroup.GENERAL, ObisInfo.groupOf("0.0.0"))
        assertEquals(ObisGroup.OTROS, ObisInfo.groupOf("C.1.0"))
        assertTrue(ReadMode.TODO.includes(ObisGroup.OTROS))
        assertTrue(!ReadMode.CONSUMO.includes(ObisGroup.FV))
        assertTrue(!ReadMode.FV.includes(ObisGroup.CONSUMO))
        assertTrue(ReadMode.FV.includes(ObisGroup.INSTANTANEO))
    }

    @Test fun obis_labels_in_spanish() {
        assertEquals("Energía activa importada total", ObisInfo.label("1.8.0"))
        assertEquals("Energía activa exportada P4", ObisInfo.label("2.8.4"))
        assertEquals("Potencia activa exportada", ObisInfo.label("2.7.0"))
        assertEquals("Tensión L1", ObisInfo.label("32.7.0"))
        assertNull(ObisInfo.label("7.7.7"))
    }

    @Test fun reading_values_filtered_by_mode_and_fv_numbers() {
        val raw = "0.0.0(ES1)\r\n1.8.0(100.0*kWh)\r\n2.8.0(40.5*kWh)\r\n2.8.1(15.5*kWh)\r\n2.7.0(1500*W)\r\n" +
            "32.7.0(230.1*V)\r\nC.1.0(abc)\r\n!\r\n"
        val r = MeterReading(0, "X", Iec62056.parseDataBlock(raw), raw)
        assertEquals(listOf("0.0.0", "1.8.0", "32.7.0"), r.valuesFor(ReadMode.CONSUMO).map { it.code })
        assertEquals(listOf("0.0.0", "2.8.0", "2.8.1", "2.7.0", "32.7.0"), r.valuesFor(ReadMode.FV).map { it.code })
        assertEquals(7, r.valuesFor(ReadMode.TODO).size)
        assertEquals(40.5, r.exportKwh!!, 1e-9)
        assertEquals(15.5, r.exportPeriodsKwh[0]!!, 1e-9)
        assertEquals(1.5, r.exportPowerKw!!, 1e-9)
    }

    @Test fun obis_log_roundtrip() {
        val v = ObisValue("1.6.1", "02.5", "kW", listOf("2610011500", "x"))
        assertEquals(1234L to v, ObisLog.parse(ObisLog.format(1234L, v)))
        val plain = ObisValue("0.0.0", "ES1", null)
        assertEquals(5L to plain, ObisLog.parse(ObisLog.format(5L, plain)))
        assertNull(ObisLog.parse("basura"))
    }

    @Test fun csv_reads_old_three_period_lines() {
        val legacy = "1000,1234.5670,400.1000,500.2000,334.2670,0.4820,231.4000,2.1000"
        val r = ReadingCsv.parse(legacy)!!
        assertEquals(1234.567, r.totalKwh!!, 1e-9)
        assertEquals(listOf(400.1, 500.2, 334.267, null, null, null), r.periodsKwh)
        assertEquals(0.482, r.powerKw!!, 1e-9)
        assertEquals(2.1, r.currentA!!, 1e-9)
        assertNull(ReadingCsv.parse("basura"))
    }

    @Test fun byte_format() {
        val b = "/?!\r\n".toByteArray() + byteArrayOf(0x00, 0xFF.toByte())
        assertEquals("/?!\\r\\n..", ByteFormat.ascii(b))
        assertEquals("2F 3F 21 0D 0A 00 FF", ByteFormat.hex(b))
        assertEquals("01 02 …", ByteFormat.hex(byteArrayOf(1, 2, 3), max = 2))
    }

    @Test fun daily_consumption() {
        fun at(day: Int, hour: Int, total: Double) = StoredReading(
            LocalDate.of(2026, 10, day).atTime(hour, 0).toInstant(ZoneOffset.UTC).toEpochMilli(),
            total, List(PERIODS) { null }, null, null, null,
        )
        val history = listOf(at(1, 8, 100.0), at(1, 20, 105.0), at(2, 20, 112.5), at(3, 8, 5.0) /* reinicio */)
        val result = dailyConsumption(history, ZoneOffset.UTC)
        assertEquals(listOf(LocalDate.of(2026, 10, 1) to 5.0, LocalDate.of(2026, 10, 2) to 7.5), result)
    }
}
