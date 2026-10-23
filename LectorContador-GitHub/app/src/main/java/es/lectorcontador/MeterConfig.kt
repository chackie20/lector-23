package es.lectorcontador

import android.content.Context

/** Protocolo que habla el puerto óptico del contador. */
enum class Protocol(val label: String) {
    IEC62056("IEC 62056-21"),
    DLMS("DLMS/COSEM"),
    ENEL("Enel 1G (automático)"),
}

/**
 * Ajustes de comunicación de un contador.
 *  - IEC 62056-21: sin ajustes (texto ASCII a 300 baudios).
 *  - DLMS/COSEM (puerto óptico, modo E): dirección de cliente, dirección de servidor (automática o fija) y,
 *    si hace falta, la contraseña de baja seguridad (LLS) del cliente, que fija la distribuidora.
 *  - Enel 1G: prueba IEC 62056-21 y, si no hay datos, DLMS con los ajustes DLMS.
 *  - [location]: ubicación que aparece en el Excel exportado.
 */
data class MeterConfig(
    val protocol: Protocol = Protocol.IEC62056,
    /** DLMS: dirección HDLC del cliente (16 = cliente público, sin contraseña). */
    val dlmsClient: Int = 16,
    /** DLMS: probar las direcciones de servidor habituales en lugar de la fija. */
    val dlmsAutoServer: Boolean = true,
    val dlmsServerLogical: Int = 1,
    /** DLMS: dirección física del servidor; 0 = sin dirección física (dirección de un solo octeto). */
    val dlmsServerPhysical: Int = 16,
    /** DLMS: contraseña LLS; vacía = sin autenticación. */
    val dlmsPassword: String = "",
    /** Ubicación del contador, para el título del Excel. */
    val location: String = "Sant Llorenç",
)

/** Contadores con acceso rápido en la pantalla de lectura. Cada uno guarda sus propios ajustes e histórico. */
enum class MeterProfile(
    val id: String,
    val title: String,
    val subtitle: String,
    val photo: Int,
    val defaults: MeterConfig,
    /** Códigos OBIS que se muestran en las columnas «Codi OBIS» del Excel. */
    val obisFamily: ObisFamily = ObisFamily.STANDARD,
) {
    ZIV(
        id = "ziv",
        title = "ZIV 5CTB (ZIVT4)",
        subtitle = "Trifásico · «CONSUM»",
        photo = R.drawable.meter_ziv,
        defaults = MeterConfig(protocol = Protocol.DLMS),
    ),
    ENEL(
        id = "enel",
        title = "Enel CERM1 (1G)",
        subtitle = "Monofásico · e-distribuzione",
        photo = R.drawable.meter_enel,
        defaults = MeterConfig(protocol = Protocol.ENEL),
        obisFamily = ObisFamily.ENEL,
    ),
}

class ConfigStore(private val context: Context) {
    private val appPrefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)

    fun activeMeter(): MeterProfile =
        MeterProfile.entries.firstOrNull { it.id == appPrefs.getString("medidor", null) } ?: MeterProfile.ZIV

    fun setActiveMeter(m: MeterProfile) {
        appPrefs.edit().putString("medidor", m.id).apply()
    }

    private fun prefs(m: MeterProfile) = context.getSharedPreferences("ajustes_${m.id}", Context.MODE_PRIVATE)

    fun load(m: MeterProfile): MeterConfig {
        val p = prefs(m)
        val d = m.defaults
        return MeterConfig(
            protocol = runCatching { Protocol.valueOf(p.getString("protocolo", "") ?: "") }.getOrDefault(d.protocol),
            dlmsClient = p.getInt("dlms_cliente", d.dlmsClient),
            dlmsAutoServer = p.getBoolean("dlms_servidor_auto", d.dlmsAutoServer),
            dlmsServerLogical = p.getInt("dlms_servidor_logica", d.dlmsServerLogical),
            dlmsServerPhysical = p.getInt("dlms_servidor_fisica", d.dlmsServerPhysical),
            dlmsPassword = p.getString("dlms_clave", d.dlmsPassword) ?: d.dlmsPassword,
            location = p.getString("ubicacion", d.location) ?: d.location,
        )
    }

    fun save(m: MeterProfile, c: MeterConfig) {
        prefs(m).edit()
            .putString("protocolo", c.protocol.name)
            .putInt("dlms_cliente", c.dlmsClient)
            .putBoolean("dlms_servidor_auto", c.dlmsAutoServer)
            .putInt("dlms_servidor_logica", c.dlmsServerLogical)
            .putInt("dlms_servidor_fisica", c.dlmsServerPhysical)
            .putString("dlms_clave", c.dlmsPassword)
            .putString("ubicacion", c.location)
            .apply()
    }
}
