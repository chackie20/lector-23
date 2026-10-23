package es.lectorcontador

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.hoho.android.usbserial.driver.UsbSerialPort
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

data class UiState(
    val usbPresent: Boolean = false,
    val busy: Boolean = false,
    val live: Boolean = false,
    val liveIntervalSec: Int = 10,
    val status: String = "",
    val error: String? = null,
    val last: MeterReading? = null,
    val history: List<StoredReading> = emptyList(),
    val diagBusy: Boolean = false,
    val diagLines: List<String> = emptyList(),
    /** Tipo de lectura elegido: decide qué registros se muestran y se guardan en detalle. */
    val mode: ReadMode = ReadMode.CONSUMO,
    /** Contador en uso: sus ajustes, su histórico y lo que se lee. */
    val meter: MeterProfile = MeterProfile.ZIV,
    /** Ajustes de comunicación de cada contador (pestaña Ajustes). */
    val configs: Map<MeterProfile, MeterConfig> = emptyMap(),
) {
    /** Ajustes del contador en uso. */
    val config: MeterConfig get() = configs[meter] ?: meter.defaults
}

class MeterViewModel(app: Application) : AndroidViewModel(app) {
    private val connector = UsbConnector(app)
    private val configStore = ConfigStore(app)
    private var store = ReadingStore(app, configStore.activeMeter())
    private val mutex = Mutex()
    private var liveJob: Job? = null

    // Escalador y unidad de cada registro DLMS: no cambian entre lecturas, así se leen una sola vez.
    private val dlmsCache = HashMap<String, Pair<Int, Int>>()

    // Registros que el contador negó o no tiene: no se vuelven a pedir en las lecturas siguientes.
    private val dlmsUnavailable = HashMap<String, Int>()

    /** Sonda y sesión DLMS que se mantienen abiertas entre lecturas del modo en vivo (evita renegociar cada vez). */
    private class LiveHold(val port: UsbSerialPort, val conn: DlmsLive, val cfg: MeterConfig)
    private var liveHold: LiveHold? = null

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    init {
        val active = configStore.activeMeter()
        _state.update { it.copy(usbPresent = connector.hasDevice(), meter = active) }
        // Ajustes e histórico se leen fuera del hilo principal para que la app abra al instante.
        viewModelScope.launch {
            val configs = withContext(Dispatchers.IO) { MeterProfile.entries.associateWith { m -> configStore.load(m) } }
            val history = withContext(Dispatchers.IO) { store.load() }
            _state.update { it.copy(configs = configs, history = history) }
        }
    }

    /** Cambia un ajuste de comunicación del contador en uso y lo guarda. */
    fun updateConfig(transform: (MeterConfig) -> MeterConfig) {
        _state.update {
            it.copy(configs = it.configs + (it.meter to transform(it.config)))
        }
        configStore.save(_state.value.meter, _state.value.config)
        // Otro cliente, contraseña o servidor: lo aprendido antes ya no vale.
        dlmsCache.clear()
        dlmsUnavailable.clear()
    }

    /** Elige el contador con el que se lee (no se puede cambiar mientras se lee). */
    fun selectMeter(m: MeterProfile) {
        val st = _state.value
        if (m == st.meter || st.live || st.busy || st.diagBusy) return
        configStore.setActiveMeter(m)
        store = ReadingStore(getApplication(), m)
        dlmsCache.clear()
        dlmsUnavailable.clear()
        val newStore = store
        viewModelScope.launch {
            val history = withContext(Dispatchers.IO) { newStore.load() }
            if (store === newStore) _state.update { it.copy(history = history) }
        }
        _state.update {
            it.copy(
                meter = m,
                history = emptyList(),
                last = null,
                error = null,
                status = "",
                diagLines = emptyList(),
            )
        }
    }

    fun refreshUsb() {
        _state.update { it.copy(usbPresent = connector.hasDevice()) }
    }

    /** Lectura puntual del tipo elegido (consumo, FV o todo): se guarda en el histórico. */
    fun readOnce(mode: ReadMode) {
        if (_state.value.live) return
        _state.update { it.copy(mode = mode) }
        viewModelScope.launch { perform(save = true) }
    }

    /** Cambia el tipo de lectura que se muestra (también en modo en vivo). */
    fun setMode(mode: ReadMode) {
        _state.update { it.copy(mode = mode) }
    }

    fun setLive(on: Boolean) {
        liveJob?.cancel()
        liveJob = null
        if (!on) {
            _state.update { it.copy(live = false, busy = false) }
            viewModelScope.launch { mutex.withLock { closeLiveHold() } }
            return
        }
        _state.update { it.copy(live = true, error = null) }
        liveJob = viewModelScope.launch {
            var failures = 0
            // Con DLMS y contraseña cada intento envía la clave de acceso: se para al primer fallo
            // para no provocar el bloqueo del contador por claves erróneas repetidas.
            val cfg0 = _state.value.config
            val maxFailures = when (cfg0.protocol) {
                Protocol.DLMS, Protocol.ENEL -> if (cfg0.dlmsPassword.isBlank()) 2 else 1
                Protocol.IEC62056 -> MAX_LIVE_FAILURES
            }
            try {
                while (isActive) {
                    if (perform(save = false, keepOpen = true)) failures = 0 else failures++
                    if (failures >= maxFailures) {
                        _state.update { it.copy(live = false) }
                        break
                    }
                    delay(_state.value.liveIntervalSec * 1000L)
                }
            } finally {
                withContext(NonCancellable) { mutex.withLock { closeLiveHold() } }
            }
        }
    }

    private fun diagLog(line: String) {
        _state.update { it.copy(diagLines = it.diagLines + line) }
    }

    /** Abre la sonda, ejecuta [block] y deja el informe en [UiState.diagLines]. */
    private fun runDiag(block: suspend (UsbSerialPort) -> Unit) {
        val st = _state.value
        if (st.live || st.diagBusy || st.busy) return
        viewModelScope.launch {
            mutex.withLock {
                _state.update { it.copy(diagBusy = true, diagLines = emptyList(), error = null) }
                try {
                    val port = connector.open()
                    try {
                        block(port)
                    } finally {
                        runCatching { port.close() }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    diagLog("Error: ${e.message ?: "desconocido"}")
                } finally {
                    _state.update { it.copy(diagBusy = false) }
                }
            }
        }
    }

    /** IEC 62056-21: prueba varias velocidades/formatos con texto ASCII. */
    fun runDiagnostic() = runDiag { port ->
        MeterDiagnostics(port) { line -> diagLog(line) }.run()
    }

    /**
     * DLMS/COSEM: negociación en modo E, enlace HDLC y asociación con los clientes habituales.
     * Si el contador contesta, guarda en Ajustes el cliente y el servidor que funcionaron.
     */
    fun runDiagnosticDlms() = runDiag { port ->
        val found = MeterDlmsDiagnostics(port, _state.value.config) { line -> diagLog(line) }.run()
        if (found != null) {
            updateConfig {
                it.copy(
                    protocol = if (it.protocol == Protocol.ENEL) Protocol.ENEL else Protocol.DLMS,
                    dlmsClient = found.client,
                    dlmsAutoServer = false,
                    dlmsServerLogical = found.server.upper,
                    dlmsServerPhysical = found.server.lower ?: 0,
                )
            }
        }
    }

    /** DLMS/COSEM: prueba la contraseña de Ajustes una vez con cada cliente habitual (no prueba otras). */
    fun runDiagnosticDlmsPassword() = runDiag { port ->
        val found = MeterDlmsDiagnostics(port, _state.value.config) { line -> diagLog(line) }.run(passwordAll = true)
        if (found != null) {
            updateConfig {
                it.copy(
                    protocol = if (it.protocol == Protocol.ENEL) Protocol.ENEL else Protocol.DLMS,
                    dlmsClient = found.client,
                    dlmsAutoServer = false,
                    dlmsServerLogical = found.server.upper,
                    dlmsServerPhysical = found.server.lower ?: 0,
                )
            }
        }
    }

    /** DLMS/COSEM: muestra la lista de objetos que el contador entrega al cliente de Ajustes. */
    fun listDlmsObjects() = runDiag { port ->
        MeterDlmsDiagnostics(port, _state.value.config) { line -> diagLog(line) }.listObjects()
    }

    fun setInterval(seconds: Int) {
        _state.update { it.copy(liveIntervalSec = seconds) }
    }

    /** Libro de Excel con las lecturas guardadas (resumen, cierres mensuales y códigos OBIS). */
    fun exportFiles(): List<File> = store.exportAll(_state.value.config.location)

    fun clearHistory() {
        store.clear()
        _state.update { it.copy(history = emptyList()) }
    }

    fun dismissError() {
        _state.update { it.copy(error = null) }
    }

    private suspend fun closeLiveHold() {
        val h = liveHold ?: return
        liveHold = null
        withContext(NonCancellable + Dispatchers.IO) {
            runCatching { h.conn.close() }
            runCatching { h.port.close() }
        }
    }

    /** Lectura DLMS en modo en vivo: reutiliza la sesión; si se ha caído, reconecta una vez. */
    private suspend fun readDlmsLive(cfg: MeterConfig, status: (String) -> Unit): MeterReading {
        fun link(port: UsbSerialPort) =
            MeterDlmsLink(port, cfg, _state.value.mode, dlmsCache, dlmsUnavailable, status) { _state.value.live }

        liveHold?.let { h ->
            if (h.cfg == cfg) {
                try {
                    return link(h.port).read(h.conn)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: DlmsAccessException) {
                    throw e
                } catch (e: IOException) {
                    status("Reconectando con el contador…")
                }
            }
            closeLiveHold()
        }
        val port = connector.open()
        val l = link(port)
        val conn = try {
            l.open()
        } catch (e: Throwable) {
            runCatching { port.close() }
            throw e
        }
        liveHold = LiveHold(port, conn, cfg)
        try {
            return l.read(conn)
        } catch (e: Throwable) {
            if (e !is DlmsAccessException) closeLiveHold()
            throw e
        }
    }

    /**
     * Contador Enel / e-distribuzione de 1ª generación. Su protocolo óptico no es público, así que se
     * prueban en orden los dos estándar: IEC 62056-21 modo C y, si no envía datos, DLMS/COSEM (modo E).
     */
    private suspend fun readEnel(port: UsbSerialPort, cfg: MeterConfig, status: (String) -> Unit): MeterReading {
        val modeCError: IOException
        try {
            status("Enel: probando IEC 62056-21 (modo C)…")
            return MeterLink(port, status).readOut()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            modeCError = e
        }
        // Se deja el contador en reposo antes de cambiar de protocolo.
        runCatching {
            port.setParameters(300, 7, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_EVEN)
            port.write(Iec62056.breakFrame(), 1000)
        }
        delay(1500)
        try {
            status("Enel: probando DLMS/COSEM (modo E)…")
            return MeterDlmsLink(port, cfg, _state.value.mode, dlmsCache, dlmsUnavailable, status).readOut()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw IOException(
                "El contador Enel no se deja leer por el puerto óptico.\n" +
                    "• Modo C (IEC 62056-21): ${modeCError.message}\n• DLMS/COSEM: ${e.message}\n" +
                    "Los Enel de 1ª generación usan un protocolo propio y suelen tener clave de acceso. " +
                    "Ejecuta el diagnóstico y comparte el informe."
            )
        }
    }

    /** @return true si la lectura fue correcta. */
    private suspend fun perform(save: Boolean, keepOpen: Boolean = false): Boolean = mutex.withLock {
        _state.update { it.copy(busy = true, error = null, status = "Conectando con la sonda…") }
        try {
            val cfg = _state.value.config
            val status = { msg: String -> _state.update { it.copy(status = msg) } }
            val reading = if (keepOpen && cfg.protocol == Protocol.DLMS) {
                readDlmsLive(cfg, status)
            } else {
                val port = connector.open()
                try {
                    when (cfg.protocol) {
                        Protocol.ENEL -> readEnel(port, cfg, status)
                        Protocol.DLMS ->
                            MeterDlmsLink(port, cfg, _state.value.mode, dlmsCache, dlmsUnavailable, status).readOut()
                        Protocol.IEC62056 -> MeterLink(port, status).readOut()
                    }
                } finally {
                    runCatching { port.close() }
                }
            }
            // Una lectura parcial (solo identificación) se muestra pero no se guarda en el histórico.
            val stored = if (save && reading.warning == null) reading.toStored() else null
            if (stored != null) {
                val mode = _state.value.mode
                val s = store
                withContext(Dispatchers.IO) {
                    s.append(stored)
                    s.appendObis(reading.timestamp, reading.valuesFor(mode))
                }
            }
            _state.update {
                it.copy(
                    last = reading,
                    usbPresent = true,
                    status = if (reading.warning == null) "Lectura correcta" else "Lectura parcial",
                    history = if (stored != null) it.history + stored else it.history,
                )
            }
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update {
                it.copy(error = e.message ?: "Error desconocido", usbPresent = connector.hasDevice())
            }
            false
        } finally {
            _state.update { it.copy(busy = false) }
        }
    }

    override fun onCleared() {
        liveHold?.let { h -> runCatching { h.port.close() } }
        liveHold = null
        super.onCleared()
    }

    private companion object {
        const val MAX_LIVE_FAILURES = 3
    }
}
