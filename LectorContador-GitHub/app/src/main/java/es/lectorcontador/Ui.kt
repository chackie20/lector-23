package es.lectorcontador

import android.content.Intent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val ES: Locale = Locale.forLanguageTag("es-ES")

private fun fmt(v: Double?, unit: String, decimals: Int = 3): String =
    if (v == null) "—" else String.format(ES, "%.${decimals}f %s", v, unit)

@Composable
fun App(vm: MeterViewModel) {
    val s by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    // Evita que la pantalla se apague durante el modo en vivo.
    val view = LocalView.current
    SideEffect { view.keepScreenOn = s.live }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceVariant) {
                NavItem(tab == 0, { tab = 0 }, Icons.Default.Home, "Lectura")
                NavItem(tab == 1, { tab = 1 }, Icons.Default.DateRange, "Histórico")
                NavItem(tab == 2, { tab = 2 }, Icons.Default.Build, "Diagnóstico")
                NavItem(tab == 3, { tab = 3 }, Icons.Default.Settings, "Ajustes")
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).statusBarsPadding()) {
            when (tab) {
                0 -> ReadingScreen(s, vm, onOpenSettings = { tab = 3 })
                1 -> HistoryScreen(s, vm)
                2 -> DiagnosticScreen(s, vm)
                else -> SettingsScreen(s, vm)
            }
        }
    }
}

@Composable
private fun RowScope.NavItem(selected: Boolean, onClick: () -> Unit, icon: ImageVector, label: String) {
    NavigationBarItem(
        selected = selected,
        onClick = onClick,
        icon = { Icon(icon, contentDescription = label) },
        label = { Text(label) },
    )
}

// ───────────────────────────── Pantalla de lectura ─────────────────────────────

@Composable
private fun ReadingScreen(s: UiState, vm: MeterViewModel, onOpenSettings: () -> Unit) {
    var showRaw by rememberSaveable { mutableStateOf(false) }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Lector de contador",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            StatusPill(s.usbPresent)
        }

        MeterCards(s, vm, onOpenSettings)

        ActionPanel(s, vm)

        if (s.busy) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(s.status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        s.error?.let { msg ->
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Column(Modifier.padding(14.dp)) {
                    Text(msg, color = MaterialTheme.colorScheme.onErrorContainer)
                    TextButton(onClick = vm::dismissError) { Text("Cerrar") }
                }
            }
        }

        s.last?.let { r ->
            ReadingCards(r, s.mode, showRaw) { showRaw = !showRaw }
        }
    }
}

/** Estado de la sonda USB en una píldora. */
@Composable
private fun StatusPill(present: Boolean) {
    val bg = if (present) MaterialTheme.colorScheme.tertiaryContainer else MaterialTheme.colorScheme.errorContainer
    val fg = if (present) MaterialTheme.colorScheme.onTertiaryContainer else MaterialTheme.colorScheme.onErrorContainer
    Surface(shape = CircleShape, color = bg) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(fg))
            Text(
                if (present) "Sonda conectada" else "Sin sonda",
                style = MaterialTheme.typography.labelMedium,
                color = fg,
            )
        }
    }
}

/** Botones de lectura, modo en vivo y vista, agrupados en una sola tarjeta. */
@Composable
private fun ActionPanel(s: UiState, vm: MeterViewModel) {
    val canRead = !s.busy && !s.live && !s.diagBusy
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Leer y guardar", style = MaterialTheme.typography.titleSmall)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.readOnce(ReadMode.CONSUMO) }, enabled = canRead, modifier = Modifier.weight(1f)) {
                    Text("Consumo")
                }
                Button(onClick = { vm.readOnce(ReadMode.FV) }, enabled = canRead, modifier = Modifier.weight(1f)) {
                    Text("FV")
                }
                Button(onClick = { vm.readOnce(ReadMode.TODO) }, enabled = canRead, modifier = Modifier.weight(1f)) {
                    Text("Todo")
                }
            }
            Text(
                "Consumo: energía importada y valores instantáneos. FV: energía y potencia exportadas. " +
                    "Todo: todos los códigos OBIS del contador.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Switch(checked = s.live, onCheckedChange = vm::setLive, enabled = !s.diagBusy)
                Text("En vivo", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                listOf(10, 30, 60).forEach { sec ->
                    FilterChip(
                        selected = s.liveIntervalSec == sec,
                        onClick = { vm.setInterval(sec) },
                        label = { Text("$sec s") },
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Ver", style = MaterialTheme.typography.bodyMedium)
                ReadMode.entries.forEach { m ->
                    FilterChip(selected = s.mode == m, onClick = { vm.setMode(m) }, label = { Text(m.label) })
                }
            }
        }
    }
}

/** Accesos rápidos: una tarjeta por contador con su foto. Al tocarla se elige y se abre su configuración. */
@Composable
private fun MeterCards(s: UiState, vm: MeterViewModel, onOpenSettings: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Mis contadores", style = MaterialTheme.typography.titleSmall)
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MeterProfile.entries.forEach { p ->
                val c = s.configs[p] ?: p.defaults
                val selected = p == s.meter
                Card(
                    Modifier.weight(1f).fillMaxHeight().clickable {
                        vm.selectMeter(p)
                        onOpenSettings()
                    },
                    border = if (selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    colors = CardDefaults.cardColors(
                        containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column {
                        Image(
                            painter = painterResource(p.photo),
                            contentDescription = "Foto del contador ${p.title}",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().aspectRatio(1.25f),
                        )
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(p.title, style = MaterialTheme.typography.titleSmall, maxLines = 2)
                            Text(
                                c.protocol.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                if (selected) "En uso · ajustes" else "Usar · ajustes",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReadingCards(r: MeterReading, mode: ReadMode, showRaw: Boolean, onToggleRaw: () -> Unit) {
    r.warning?.let { w ->
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
            Text(
                w,
                Modifier.padding(14.dp),
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    if (mode != ReadMode.FV && r.totalKwh != null) {
        HeroCard("Energía activa total (consumo)", fmt(r.totalKwh, "kWh"), r.periodsKwh, "Energía por periodo")
        InstantTiles(r)
    }
    if (mode != ReadMode.CONSUMO && r.exportKwh != null) {
        HeroCard("Energía exportada (FV)", fmt(r.exportKwh, "kWh"), r.exportPeriodsKwh, "Exportada por periodo")
        r.exportPowerKw?.let { Text("Potencia exportada: ${fmt(it, "kW")}", style = MaterialTheme.typography.bodyMedium) }
    }
    if (mode == ReadMode.FV && r.exportKwh == null) {
        Text("El contador no ha entregado energía exportada.", style = MaterialTheme.typography.bodyMedium)
    }
    val shown = r.valuesFor(mode)
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Contador", style = MaterialTheme.typography.labelLarge)
            Text(r.identification.ifBlank { "—" })
            r.serial?.let { Text("Nº de serie: $it") }
            Text(
                "Leído el " + DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss")
                    .withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(r.timestamp)),
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = onToggleRaw) {
                Text(if (showRaw) "Ocultar valores OBIS" else "Ver valores OBIS (${shown.size})")
            }
            if (showRaw) {
                shown.forEach { v ->
                    val label = ObisInfo.label(v.code)
                    Text(
                        if (label != null) "${v.code} · $label" else v.code,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    Text(obisText(v), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Tarjeta principal: total en grande y reparto por periodos con barras proporcionales. */
@Composable
private fun HeroCard(title: String, total: String, periods: List<Double?>, periodsTitle: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(total, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            PeriodRows(periodsTitle, periods)
        }
    }
}

/** Potencia, tensión e intensidad instantáneas en mosaicos. */
@Composable
private fun InstantTiles(r: MeterReading) {
    val tiles = listOfNotNull(
        r.powerKw?.let { "Potencia" to fmt(it, "kW") },
        r.voltageV?.let { "Tensión" to fmt(it, "V", 1) },
        r.currentA?.let { "Intensidad" to fmt(it, "A", 2) },
    )
    if (tiles.isEmpty()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tiles.forEach { (label, value) ->
            Card(
                Modifier.weight(1f),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text(
                        value,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
        }
    }
}

@Composable
private fun PeriodRows(title: String, periods: List<Double?>) {
    val present = periods.withIndex().filter { it.value != null }
    if (present.isEmpty()) return
    val max = present.maxOf { it.value!! }.coerceAtLeast(0.000001)
    Text(title, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp))
    present.forEach { (i, v) ->
        Column(Modifier.padding(top = 4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("P${i + 1}", style = MaterialTheme.typography.bodyMedium)
                Text(fmt(v, "kWh"), style = MaterialTheme.typography.bodyMedium)
            }
            LinearProgressIndicator(
                progress = { (v!! / max).toFloat().coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
            )
        }
    }
}

/** "001234.567 kWh (2610011500)": valor, unidad y datos extra de la línea OBIS. */
private fun obisText(v: ObisValue): String =
    v.value + (v.unit?.let { " $it" } ?: "") + v.extras.joinToString("") { " ($it)" }

// ───────────────────────────── Pantalla de histórico ─────────────────────────────

@Composable
private fun HistoryScreen(s: UiState, vm: MeterViewModel) {
    val ctx = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    val daily = remember(s.history) { dailyConsumption(s.history) }
    val rowFmt = remember { DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.systemDefault()) }

    fun export() {
        // Libro de Excel con el resumen, los cierres mensuales y los códigos OBIS.
        val files = vm.exportFiles()
        if (files.isEmpty()) return
        val uris = ArrayList(files.map { FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", it) })
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(send, "Exportar lecturas"))
    }

    LazyColumn(
        Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Histórico", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 16.dp))
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::export, enabled = s.history.isNotEmpty()) { Text("Exportar Excel") }
                OutlinedButton(onClick = { confirmClear = true }, enabled = s.history.isNotEmpty()) { Text("Borrar todo") }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text("Consumo diario (kWh)", style = MaterialTheme.typography.labelLarge)
                    if (daily.isEmpty()) {
                        Text(
                            "Hacen falta al menos dos lecturas guardadas para calcular consumos.",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    } else {
                        DailyBarChart(daily, Modifier.padding(top = 8.dp))
                        Text(
                            "Diferencia entre lecturas consecutivas, asignada al día de la lectura más reciente.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
        item { Text("Lecturas guardadas (${s.history.size})", style = MaterialTheme.typography.labelLarge) }
        items(s.history.asReversed().take(100)) { r ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(rowFmt.format(Instant.ofEpochMilli(r.epochMs)), style = MaterialTheme.typography.bodyMedium)
                Text(
                    fmt(r.totalKwh, "kWh") + (r.exportKwh?.let { "  ·  FV ${fmt(it, "kWh")}" } ?: ""),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
        item { Text("", Modifier.height(16.dp)) }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("¿Borrar el histórico?") },
            text = { Text("Se eliminarán todas las lecturas guardadas en este móvil. Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(onClick = { vm.clearHistory(); confirmClear = false }) { Text("Borrar") }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancelar") } },
        )
    }
}

@Composable
private fun DailyBarChart(data: List<Pair<LocalDate, Double>>, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val barColor = MaterialTheme.colorScheme.primary
    val labelStyle = TextStyle(fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val dateFmt = remember { DateTimeFormatter.ofPattern("dd/MM") }

    Canvas(modifier.fillMaxWidth().height(200.dp)) {
        if (data.isEmpty()) return@Canvas
        val maxV = data.maxOf { it.second }.coerceAtLeast(0.001)
        val topPad = 18.dp.toPx()
        val bottomPad = 18.dp.toPx()
        val chartH = size.height - topPad - bottomPad
        val slot = size.width / data.size
        val barW = slot * 0.6f

        data.forEachIndexed { i, (date, value) ->
            val h = (value / maxV).toFloat() * chartH
            val x = i * slot + (slot - barW) / 2f
            drawRect(barColor, topLeft = Offset(x, topPad + chartH - h), size = Size(barW, h))

            val valueText = measurer.measure(String.format(ES, "%.1f", value), labelStyle)
            drawText(
                valueText,
                topLeft = Offset(i * slot + (slot - valueText.size.width) / 2f, topPad + chartH - h - valueText.size.height),
            )
            val dateText = measurer.measure(date.format(dateFmt), labelStyle)
            drawText(
                dateText,
                topLeft = Offset(i * slot + (slot - dateText.size.width) / 2f, size.height - bottomPad + 2.dp.toPx()),
            )
        }
    }
}
