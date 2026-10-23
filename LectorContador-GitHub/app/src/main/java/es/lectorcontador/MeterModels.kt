package es.lectorcontador

/**
 * Un registro OBIS leído del contador, p. ej. code="1.8.0", value="001234.567", unit="kWh".
 * [extras] son los demás paréntesis de la línea, p. ej. la fecha de una máxima demanda:
 * `1.6.1(02.5*kW)(2610011500)` → value="02.5", unit="kW", extras=["2610011500"].
 */
data class ObisValue(
    val code: String,
    val value: String,
    val unit: String?,
    val extras: List<String> = emptyList(),
)

/** Número máximo de periodos tarifarios (P1..P6, como en las tarifas 3.0TD y 6.1TD). */
const val PERIODS = 6

/** Lectura tal y como se guarda en el histórico. [periodsKwh] siempre tiene [PERIODS] elementos. */
data class StoredReading(
    val epochMs: Long,
    val totalKwh: Double?,
    val periodsKwh: List<Double?>,
    val powerKw: Double?,
    val voltageV: Double?,
    val currentA: Double?,
    val exportKwh: Double? = null,
    val exportPeriodsKwh: List<Double?> = List(PERIODS) { null },
    val exportPowerKw: Double? = null,
    /** Energía reactiva importada por periodo P1..P6 (kvarh); null si el contador no la entrega. */
    val reactivePeriodsKvarh: List<Double?> = List(PERIODS) { null },
)

/** Resultado completo de una lectura del puerto óptico. */
data class MeterReading(
    val timestamp: Long,
    val identification: String,
    val values: List<ObisValue>,
    val raw: String,
    /** Aviso cuando la lectura es parcial (p. ej. el contador solo entrega la identificación). */
    val warning: String? = null,
) {
    private fun find(code: String): ObisValue? = values.firstOrNull { it.code == code }

    /** Valor numérico normalizado: W→kW y Wh→kWh. */
    private fun number(code: String): Double? {
        val v = find(code) ?: return null
        val n = v.value.trim().replace(',', '.').toDoubleOrNull() ?: return null
        return if (v.unit == "Wh" || v.unit == "W" || v.unit == "varh" || v.unit == "var") n / 1000.0 else n
    }

    /**
     * Energía activa importada por periodo: P1..P6 = registros OBIS 1.8.1 .. 1.8.6, o 1.18.1 .. 1.18.6
     * (códigos de la pantalla de los Enel). Null si no existe.
     */
    val periodsKwh: List<Double?> get() = (1..PERIODS).map { number("1.8.$it") ?: number("1.18.$it") }

    /** Energía reactiva importada por periodo: 3.8.1 .. 3.8.6, o 1.58.1 .. 1.58.6 (Enel). */
    val reactivePeriodsKvarh: List<Double?>
        get() = (1..PERIODS).map { number("3.8.$it") ?: number("1.58.$it") }

    /** Energía activa importada total; si el contador no da 1.8.0 se suman los periodos. */
    val totalKwh: Double?
        get() = number("1.8.0") ?: number("1.18.0")
            ?: periodsKwh.filterNotNull().takeIf { it.isNotEmpty() }?.sum()

    /** Energía activa exportada (fotovoltaica / vertido): 2.8.0 y periodos 2.8.1 .. 2.8.6. */
    val exportPeriodsKwh: List<Double?> get() = (1..PERIODS).map { number("2.8.$it") }
    val exportKwh: Double?
        get() = number("2.8.0")
            ?: exportPeriodsKwh.filterNotNull().takeIf { it.isNotEmpty() }?.sum()
    val exportPowerKw: Double? get() = number("2.7.0")

    /** Registros que corresponden al modo elegido (consumo, FV o todos). */
    fun valuesFor(mode: ReadMode): List<ObisValue> =
        values.filter { mode.includes(ObisInfo.groupOf(it.code)) }

    val powerKw: Double? get() = number("1.7.0")
    val voltageV: Double? get() = number("32.7.0")
    val currentA: Double? get() = number("31.7.0")

    val serial: String?
        get() = find("0.0.0")?.value ?: find("96.1.1")?.value ?: find("0-0:96.1.1")?.value ?: find("0.0.1")?.value

    fun toStored() = StoredReading(
        timestamp, totalKwh, periodsKwh, powerKw, voltageV, currentA,
        exportKwh, exportPeriodsKwh, exportPowerKw, reactivePeriodsKvarh,
    )
}
