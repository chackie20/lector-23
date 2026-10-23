package es.lectorcontador

import android.os.SystemClock
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** Bytes recibidos y si cumplen la condición de fin ([complete]). */
class ReadResult(val bytes: ByteArray, val complete: Boolean)

/**
 * Lee del puerto hasta que [done] devuelve true, se agota [timeoutMs] o, si no ha llegado ni un byte,
 * pasan [silentMs] (para no esperar 20 s a un contador que no va a contestar).
 */
internal suspend fun UsbSerialPort.collect(
    timeoutMs: Long,
    silentMs: Long = timeoutMs,
    done: (ByteArray) -> Boolean,
): ReadResult {
    val acc = ByteArrayOutputStream()
    val buf = ByteArray(1024)
    val start = SystemClock.elapsedRealtime()
    val deadline = start + timeoutMs
    while (true) {
        val now = SystemClock.elapsedRealtime()
        if (now >= deadline) break
        if (acc.size() == 0 && now - start >= silentMs) break
        coroutineContext.ensureActive()
        val n = read(buf, 200)
        if (n > 0) {
            acc.write(buf, 0, n)
            val current = acc.toByteArray()
            if (done(current)) return ReadResult(current, true)
        }
    }
    return ReadResult(acc.toByteArray(), false)
}

/** Texto ASCII de lo recibido, ignorando el bit de paridad (7E1). */
internal fun asciiOf(bytes: ByteArray): String =
    String(ByteArray(bytes.size) { (bytes[it].toInt() and 0x7F).toByte() }, Charsets.US_ASCII)
