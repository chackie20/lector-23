package es.lectorcontador

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun hex(s: String) = Dlms.bytesOf(s)

class DlmsTest {

    @Test fun crc_check_value() {
        assertEquals(0x906E, Crc16.x25("123456789".toByteArray()))
    }

    @Test fun snrm_frames() {
        assertArrayEquals(hex("7E A0 07 03 21 93 0F 01 7E"), Hdlc.build(HdlcAddress(1), HdlcAddress(16), Hdlc.SNRM))
    }

    @Test fun aarq_bytes() {
        assertArrayEquals(
            hex("60 1D A1 09 06 07 60 85 74 05 08 01 01 BE 10 04 0E 01 00 00 00 06 5F 1F 04 00 00 7E 1F 04 B0"),
            Dlms.aarq(null),
        )
        val lls = Dlms.aarq("1234")
        assertEquals(0x60, lls[0].toInt())
        assertTrue(ByteFormat.hex(lls).contains("8A 02 07 80"))
        assertTrue(ByteFormat.hex(lls).contains("AC 06 80 04 31 32 33 34"))
    }

    @Test fun aare_parse() {
        val ok = hex("61 29 A1 09 06 07 60 85 74 05 08 01 01 A2 03 02 01 00 A3 05 A1 03 02 01 00")
        val a = Dlms.parseAare(ok)!!
        assertEquals(0, a.result)
        val bad = hex("61 29 A1 09 06 07 60 85 74 05 08 01 01 A2 03 02 01 01 A3 05 A1 03 02 01 0D")
        val b = Dlms.parseAare(bad)!!
        assertEquals(1, b.result)
        assertEquals(13, b.diagnostic)
        assertTrue(Dlms.aareFailureText(b).contains("contraseña"))
        assertNull(Dlms.parseAare(hex("00 01")))
    }

    @Test fun get_request_and_response() {
        assertArrayEquals(
            hex("C0 01 C1 00 03 01 00 01 08 00 FF 02 00"),
            Dlms.getRequest(1, 3, Dlms.obis(1, 0, 1, 8, 0), 2),
        )
        val v = Dlms.parseGetResponse(hex("C4 01 C1 00 06 00 00 30 39")) as GetResult.Value
        assertEquals(DlmsData.Num(12345), v.data)
        val f = Dlms.parseGetResponse(hex("C4 01 C1 01 03")) as GetResult.Failure
        assertEquals(3, f.code)
        assertTrue(Dlms.isDenied(3))
        assertTrue(Dlms.isMissing(4))
    }

    @Test fun data_types_and_scaler() {
        assertEquals(DlmsData.Num(-2), Dlms.parseData(hex("0F FE"))!!.first)
        assertEquals(DlmsData.Num(300), Dlms.parseData(hex("12 01 2C"))!!.first)
        assertEquals(DlmsData.Text("AB"), Dlms.parseData(hex("0A 02 41 42"))!!.first)
        val su = Dlms.parseScalerUnit(Dlms.parseData(hex("02 02 0F FD 16 1E"))!!.first)!!
        assertEquals(-3 to 30, su)
        assertEquals("1.234", Dlms.scaledText(DlmsData.Num(1234), -3))
        assertEquals("Wh", Dlms.unitText(30))
        val clock = Dlms.clockText(hex("07 EA 0A 16 05 08 1E 00 00 00 00 00"))!!
        assertEquals("2026-10-22", clock.first)
        assertEquals("08:30:00", clock.second)
    }

    @Test fun hdlc_roundtrip_and_garbage() {
        val info = Hdlc.LLC_COMMAND + hex("C0 01 C1 00 03 01 00 01 08 00 FF 02 00")
        val frame = Hdlc.build(HdlcAddress(1, 16), HdlcAddress(16), Hdlc.infoControl(0, 0), info)
        val ex = Hdlc.extract(hex("00 55") + frame + frame.copyOf(5))
        assertEquals(1, ex.frames.size)
        val f = ex.frames[0]
        assertEquals(HdlcAddress(1, 16), f.dst)
        assertEquals(HdlcAddress(16), f.src)
        assertArrayEquals(info, f.info)
        assertTrue(f.isInfo)
        // CRC erróneo se descarta
        val bad = frame.copyOf(); bad[bad.size - 3] = (bad[bad.size - 3] + 1).toByte()
        assertEquals(0, Hdlc.extract(bad).frames.size)
    }

    @Test fun frame_with_flag_byte_inside_payload() {
        // El AARQ contiene 7E (conformidad 00 7E 1F): la trama no debe cortarse ahí.
        val raw = Hdlc.build(HdlcAddress(1, 16), HdlcAddress(16), Hdlc.infoControl(0, 0), Hdlc.LLC_COMMAND + Dlms.aarq(null))
        val ex = Hdlc.extract(raw)
        assertEquals(1, ex.frames.size)
        assertArrayEquals(Hdlc.LLC_COMMAND + Dlms.aarq(null), ex.frames[0].info)
        // trama partida en dos lecturas del puerto
        val first = Hdlc.extract(raw.copyOf(20))
        assertEquals(0, first.frames.size)
        assertEquals(1, Hdlc.extract(first.rest + raw.copyOfRange(20, raw.size)).frames.size)
    }

    @Test fun password_bytes_ascii_or_hex() {
        assertArrayEquals("00000001".toByteArray(), Dlms.passwordBytes("00000001"))
        assertArrayEquals(hex("30 30 31"), Dlms.passwordBytes("0x303031"))
        // "0x" con longitud impar o no hexadecimal se trata como texto
        assertArrayEquals("0x123".toByteArray(), Dlms.passwordBytes("0x123"))
        assertArrayEquals("0xZZ".toByteArray(), Dlms.passwordBytes("0xZZ"))
        assertTrue(ByteFormat.hex(Dlms.aarq("0x3030")).contains("AC 04 80 02 30 30"))
    }

    @Test fun unavailable_objects_are_not_requested_again() {
        val server = HdlcAddress(1, 16)
        val meter = FakeMeter(server, null)
        val dlms = DlmsClient(meter, HdlcAddress(16), server)
        assertTrue(dlms.connect())
        dlms.associate(null)
        val cache = HashMap<String, Pair<Int, Int>>()
        val unavailable = HashMap<String, Int>()
        val specs = DlmsCatalog.forMode(ReadMode.CONSUMO)
        val first = DlmsReader.read(dlms, specs, cache, {}, unavailable)
        assertTrue(unavailable.isNotEmpty())
        val before = meter.gets
        val second = DlmsReader.read(dlms, specs, cache, {}, unavailable)
        val secondGets = meter.gets - before
        assertEquals(first.values.size, second.values.size)
        assertEquals(first.missing, second.missing)
        // segunda lectura: solo los registros presentes (sin escaladores ni inexistentes)
        assertEquals(first.values.count { it.code != "0.9.2" }.coerceAtLeast(1) , second.values.count { it.code != "0.9.2" })
        assertTrue(secondGets <= 5)
    }

    @Test fun server_candidates_and_ack() {
        assertEquals(listOf(HdlcAddress(1, 16), HdlcAddress(1), HdlcAddress(1, 1)), DlmsSetup.serverCandidates(true, 1, 16))
        assertEquals(listOf(HdlcAddress(2, 5)), DlmsSetup.serverCandidates(false, 2, 5))
        assertEquals(listOf(HdlcAddress(2)), DlmsSetup.serverCandidates(false, 2, 0))
        assertArrayEquals(hex("06 32 35 32 0D 0A"), DlmsSetup.modeEAck('5'))
    }

    /** Contador simulado: contesta SNRM, AARQ y GET como un ZIV con registros de energía. */
    private class FakeMeter(val server: HdlcAddress, val requirePassword: String?) : HdlcTransport {
        val out = ArrayDeque<HdlcFrame>()
        var vs = 0
        var vr = 0
        var associated = false
        var gets = 0
        override fun send(bytes: ByteArray) {
            for (f in Hdlc.extract(bytes).frames) {
                if (f.dst != server) continue
                val c = f.src
                when {
                    f.control == Hdlc.SNRM -> { vs = 0; vr = 0; reply(c, 0x73, ByteArray(0)) }
                    f.control == Hdlc.DISC -> reply(c, 0x73, ByteArray(0))
                    f.isInfo -> {
                        vr = (f.ns + 1) and 7
                        val apdu = Dlms.stripLlc(f.info)
                        val resp = handle(apdu)
                        reply(c, Hdlc.infoControl(vs, vr), hex("E6 E7 00") + resp)
                        vs = (vs + 1) and 7
                    }
                }
            }
        }
        private fun reply(c: HdlcAddress, control: Int, info: ByteArray) {
            val raw = Hdlc.build(c, server, control, info)
            out += Hdlc.extract(raw).frames
        }
        private fun handle(a: ByteArray): ByteArray {
            if (a[0] == 0x60.toByte()) {
                val pwOk = requirePassword == null || ByteFormat.hex(a).contains(
                    "AC ${"%02X".format(requirePassword.length + 2)} 80 ${"%02X".format(requirePassword.length)} " +
                        requirePassword.map { "%02X".format(it.code) }.joinToString(" "),
                )
                associated = pwOk
                val diag = if (pwOk) "00" else "0D"
                val res = if (pwOk) "00" else "01"
                return hex("61 29 A1 09 06 07 60 85 74 05 08 01 01 A2 03 02 01 $res A3 05 A1 03 02 01 $diag")
            }
            if (a[0] == 0xC0.toByte()) {
                gets++
                val id = a[2]
                val cls = ((a[3].toInt() and 0xFF) shl 8) or (a[4].toInt() and 0xFF)
                val obis = a.copyOfRange(5, 11)
                val attr = a[11].toInt()
                val key = ByteFormat.hex(obis).replace(" ", "")
                val energy = key.startsWith("0100") || key.startsWith("0200") || key.startsWith("0300")
                if (!associated && energy) return byteArrayOf(0xC4.toByte(), 1, id, 1, 3)
                fun ok(d: String) = hex("C4 01") + byteArrayOf(id, 0) + hex(d)
                return when {
                    key == "0000600101FF" -> ok("09 04 5A 49 56 31")
                    key == "0000010000FF" -> ok("09 0C 07 EA 0A 16 05 08 1E 00 00 00 00 00")
                    key == "0100010800FF" && attr == 2 -> ok("06 00 12 D6 87")
                    key == "0100010800FF" && attr == 3 -> ok("02 02 0F FD 16 1E")
                    key == "0100010700FF" && attr == 2 -> ok("06 00 00 04 D2")
                    key == "0100010700FF" && attr == 3 -> ok("02 02 0F 00 16 1B")
                    else -> byteArrayOf(0xC4.toByte(), 1, id, 1, 4)
                }
            }
            return ByteArray(0)
        }
        override fun receive(timeoutMs: Long, accept: (HdlcFrame) -> Boolean): HdlcFrame? {
            while (out.isNotEmpty()) {
                val f = out.removeFirst()
                if (accept(f)) return f
            }
            return null
        }
    }

    @Test fun full_read_with_simulated_meter() {
        val server = HdlcAddress(1, 16)
        val meter = FakeMeter(server, null)
        val dlms = DlmsClient(meter, HdlcAddress(16), server)
        assertTrue(dlms.connect())
        assertEquals(0, dlms.associate(null).result)
        val res = DlmsReader.read(dlms, DlmsCatalog.forMode(ReadMode.CONSUMO), HashMap())
        assertEquals(2, res.scaledRead)
        val e = res.values.first { it.code == "1.8.0" }
        assertEquals("1234.567", e.value)
        assertEquals("Wh", e.unit)
        assertEquals("1234", res.values.first { it.code == "1.7.0" }.value)
        assertEquals("ZIV1", res.values.first { it.code == "96.1.1" }.value)
        assertNotNull(res.values.firstOrNull { it.code == "0.9.1" })
        assertTrue(res.missing > 0)
        dlms.disconnect()
    }

    @Test fun wrong_server_does_not_answer() {
        val meter = FakeMeter(HdlcAddress(1, 16), null)
        val dlms = DlmsClient(meter, HdlcAddress(16), HdlcAddress(1))
        assertFalse(dlms.connect(attempts = 1))
    }

    @Test fun password_required() {
        val server = HdlcAddress(1, 16)
        val meter = FakeMeter(server, "00000001")
        val dlms = DlmsClient(meter, HdlcAddress(1), server)
        assertTrue(dlms.connect())
        val bad = dlms.associate(null)
        assertEquals(1, bad.result)
        val res = DlmsReader.read(dlms, DlmsCatalog.forMode(ReadMode.CONSUMO), HashMap())
        assertEquals(0, res.scaledRead)
        assertTrue(res.denied > 0)
        assertEquals(0, dlms.associate("00000001").result)
        val ok = DlmsReader.read(dlms, DlmsCatalog.forMode(ReadMode.CONSUMO), HashMap())
        assertEquals(2, ok.scaledRead)
    }

    @Test fun discovery_object_list() {
        val data = Dlms.parseData(
            hex("01 02 02 04 12 00 03 11 01 09 06 01 00 01 08 00 FF 02 02 01 00 00 02 04 12 00 08 11 00 09 06 00 00 01 00 00 FF 02 02 01 00 00")
        )!!.first
        val objs = DlmsDiscovery.parseObjectList(data)
        assertEquals(2, objs.size)
        assertEquals(3, objs[0].classId)
        assertEquals("1-0:1.8.0.255", objs[0].obis)
    }
}
