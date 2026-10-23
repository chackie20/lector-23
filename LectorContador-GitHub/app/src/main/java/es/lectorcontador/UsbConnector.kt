package es.lectorcontador

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.suspendCancellableCoroutine

/** Localiza la sonda USB, pide permiso al usuario y abre el puerto serie. */
class UsbConnector(context: Context) {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(Context.USB_SERVICE) as UsbManager

    private fun findDriver(): UsbSerialDriver? =
        UsbSerialProber.getDefaultProber().findAllDrivers(manager).firstOrNull()

    fun hasDevice(): Boolean = findDriver() != null

    suspend fun open(): UsbSerialPort {
        val driver = findDriver()
            ?: throw IOException("No se detecta ninguna sonda USB. Conecta el adaptador OTG y la sonda.")
        val device = driver.device
        if (!manager.hasPermission(device)) requestPermission(device)

        val connection = manager.openDevice(device)
            ?: throw IOException("No se pudo abrir el dispositivo USB.")
        val port = driver.ports.firstOrNull()
            ?: throw IOException("La sonda no expone ningún puerto serie.")
        try {
            port.open(connection)
        } catch (e: IOException) {
            connection.close()
            throw e
        }
        return port
    }

    private suspend fun requestPermission(device: UsbDevice) {
        suspendCancellableCoroutine<Unit> { cont ->
            val action = PERMISSION_ACTION
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    runCatching { appContext.unregisterReceiver(this) }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (!cont.isActive) return
                    if (granted) cont.resume(Unit)
                    else cont.resumeWithException(IOException("Permiso USB denegado."))
                }
            }
            ContextCompat.registerReceiver(
                appContext, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            cont.invokeOnCancellation { runCatching { appContext.unregisterReceiver(receiver) } }

            // FLAG_MUTABLE es obligatorio: el sistema rellena EXTRA_PERMISSION_GRANTED.
            val pending = PendingIntent.getBroadcast(
                appContext, 0,
                Intent(action).setPackage(appContext.packageName),
                PendingIntent.FLAG_MUTABLE,
            )
            manager.requestPermission(device, pending)
        }
    }

    private companion object {
        const val PERMISSION_ACTION = "es.lectorcontador.USB_PERMISSION"
    }
}
