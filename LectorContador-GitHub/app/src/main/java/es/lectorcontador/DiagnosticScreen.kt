package es.lectorcontador

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun DiagnosticScreen(s: UiState, vm: MeterViewModel) {
    val ctx = LocalContext.current
    Column(
        Modifier.verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Diagnóstico", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Prueba varias velocidades y formatos en el puerto óptico y muestra los bytes que llegan, " +
                "para saber qué protocolo habla el contador. Dura unos 40 segundos. " +
                "Coloca la sonda sobre el puerto óptico antes de empezar. " +
                "El primer botón comprueba IEC 62056-21 (texto).",
            style = MaterialTheme.typography.bodyMedium,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::runDiagnostic, enabled = !s.diagBusy && !s.busy && !s.live) {
                Text("Ejecutar diagnóstico")
            }
            OutlinedButton(
                enabled = s.diagLines.isNotEmpty() && !s.diagBusy,
                onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "Informe de diagnóstico del contador")
                        putExtra(Intent.EXTRA_TEXT, s.diagLines.joinToString("\n"))
                    }
                    ctx.startActivity(Intent.createChooser(send, "Compartir informe"))
                },
            ) { Text("Compartir informe") }
        }
        Text(
            "Contadores inteligentes con DLMS/COSEM (p. ej. ZIV 5CTB): el diagnóstico negocia el modo E, abre " +
                "el enlace HDLC y prueba los clientes habituales sin contraseña (solo lecturas). " +
                "«Listar objetos» enseña qué registros entrega el contador con el cliente y la contraseña de Ajustes.",
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = vm::runDiagnosticDlms,
                enabled = !s.diagBusy && !s.busy && !s.live,
            ) { Text("Diagnóstico DLMS") }
            OutlinedButton(
                onClick = vm::listDlmsObjects,
                enabled = !s.diagBusy && !s.busy && !s.live,
            ) { Text("Listar objetos") }
        }
        OutlinedButton(
            onClick = vm::runDiagnosticDlmsPassword,
            enabled = !s.diagBusy && !s.busy && !s.live && s.config.dlmsPassword.isNotBlank(),
        ) { Text("Probar mi contraseña con cada cliente") }
        Text(
            "Prueba la contraseña escrita en Ajustes una sola vez con cada cliente (1, 2, 3, 17, 32, 48), " +
                "para averiguar a cuál corresponde. No prueba otras contraseñas: los contadores pueden bloquear " +
                "el acceso tras varios fallos.",
            style = MaterialTheme.typography.bodySmall,
        )
        if (s.diagBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        if (s.live) {
            Text("Desactiva el modo en vivo para ejecutar el diagnóstico.", style = MaterialTheme.typography.bodySmall)
        }
        s.diagLines.forEach { line ->
            Text(line, fontFamily = FontFamily.Monospace, fontSize = 12.sp)
        }
    }
}
