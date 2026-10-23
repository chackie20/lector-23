package es.lectorcontador

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun SettingsScreen(s: UiState, vm: MeterViewModel) {
    val c = s.config
    var showPassword by remember { mutableStateOf(false) }
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Ajustes", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Contador: ${s.meter.title}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Cada contador guarda sus propios ajustes e histórico. Para cambiar de contador, " +
                "toca su tarjeta en la pantalla Lectura.",
            style = MaterialTheme.typography.bodySmall,
        )

        OutlinedTextField(
            value = c.location,
            onValueChange = { new -> vm.updateConfig { it.copy(location = new.take(40)) } },
            label = { Text("Ubicación (título del Excel exportado)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text("Protocolo del contador", style = MaterialTheme.typography.labelLarge)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Protocol.entries.forEach { p ->
                FilterChip(
                    selected = c.protocol == p,
                    onClick = { vm.updateConfig { it.copy(protocol = p) } },
                    label = { Text(p.label) },
                )
            }
        }

        if (c.protocol == Protocol.IEC62056) {
            Text(
                "Texto ASCII a 300 baudios (7E1) que cambia de velocidad. Es el habitual en contadores de " +
                    "los años 2010 en adelante con puerto óptico estándar.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            if (c.protocol == Protocol.ENEL) {
                Text(
                    "Contador Enel / e-distribuzione de 1ª generación: la app prueba primero IEC 62056-21 y, si " +
                        "no envía datos, DLMS/COSEM con los ajustes de abajo. Su protocolo no es público y suele " +
                        "tener clave de acceso, así que puede no responder.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "DLMS/COSEM por el puerto óptico (contadores inteligentes como el ZIV 5CTB). El contador se " +
                    "identifica en texto y después habla en binario (HDLC). Con el cliente público (16) " +
                    "normalmente solo se leen datos de identificación: para la energía suele hacer falta " +
                    "el cliente y la contraseña de lectura que fija la distribuidora. Si no los tienes, " +
                    "usa «Diagnóstico DLMS» para ver con qué cliente responde.",
                style = MaterialTheme.typography.bodySmall,
            )
            NumberField("Cliente (dirección HDLC, 1–127)", c.dlmsClient.toString(), 127) { v ->
                vm.updateConfig { it.copy(dlmsClient = v.toInt()) }
            }
            Text("Clientes habituales: 16 público, 1 y 2 lectura.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(
                value = c.dlmsPassword,
                onValueChange = { new -> vm.updateConfig { it.copy(dlmsPassword = new.take(32)) } },
                label = { Text("Contraseña de lectura (vacía = sin contraseña)") },
                singleLine = true,
                visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Mostrar contraseña", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = showPassword, onCheckedChange = { showPassword = it })
            }
            Text(
                "Se envía tal cual en ASCII. Si empieza por 0x se interpreta como bytes en hexadecimal " +
                    "(p. ej. 0x3030303030303031). Respeta ceros iniciales y mayúsculas.",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "Cada lectura envía la contraseña una vez. Con una contraseña incorrecta algunos contadores " +
                    "bloquean el acceso tras varios fallos: no insistas si falla.",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Dirección de servidor automática")
                Switch(
                    checked = c.dlmsAutoServer,
                    onCheckedChange = { on -> vm.updateConfig { it.copy(dlmsAutoServer = on) } },
                )
            }
            if (!c.dlmsAutoServer) {
                NumberField("Servidor lógico (1–127)", c.dlmsServerLogical.toString(), 127) { v ->
                    vm.updateConfig { it.copy(dlmsServerLogical = v.toInt()) }
                }
                NumberField("Servidor físico (0 = sin dirección física, 1–16383)", c.dlmsServerPhysical.toString(), 16383) { v ->
                    vm.updateConfig { it.copy(dlmsServerPhysical = v.toInt()) }
                }
            }
        }
    }
}

@Composable
private fun NumberField(label: String, initial: String, max: Long, onValid: (Long) -> Unit) {
    var text by remember(initial) { mutableStateOf(initial) }
    OutlinedTextField(
        value = text,
        onValueChange = { new ->
            val digits = new.filter { it.isDigit() }.take(10)
            text = digits
            digits.toLongOrNull()?.takeIf { it <= max }?.let(onValid)
        },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}
