package es.lectorcontador

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val vm: MeterViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MeterTheme { App(vm) } }
    }

    // Se llega aquí al conectar la sonda con la app ya abierta (launchMode=singleTop).
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.refreshUsb()
    }

    override fun onResume() {
        super.onResume()
        vm.refreshUsb()
    }
}

private val LightColors = lightColorScheme(
    primary = Color(0xFF1B5E9E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD4E6FB),
    onPrimaryContainer = Color(0xFF00213F),
    secondary = Color(0xFFB36B00),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFFFE2B8),
    onSecondaryContainer = Color(0xFF2B1700),
    tertiary = Color(0xFF2E7D4F),
    tertiaryContainer = Color(0xFFCDEED8),
    onTertiaryContainer = Color(0xFF00210F),
    background = Color(0xFFF6F8FB),
    surface = Color(0xFFF6F8FB),
    surfaceVariant = Color(0xFFE6EBF2),
    onSurfaceVariant = Color(0xFF424A55),
    outline = Color(0xFF727B87),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF9DCBFF),
    onPrimary = Color(0xFF00325A),
    primaryContainer = Color(0xFF0F4A7E),
    onPrimaryContainer = Color(0xFFD4E6FB),
    secondary = Color(0xFFFFB85C),
    onSecondary = Color(0xFF4A2800),
    secondaryContainer = Color(0xFF6A3E00),
    onSecondaryContainer = Color(0xFFFFE2B8),
    tertiary = Color(0xFF93D5A9),
    tertiaryContainer = Color(0xFF0F5233),
    onTertiaryContainer = Color(0xFFCDEED8),
    background = Color(0xFF0F1318),
    surface = Color(0xFF0F1318),
    surfaceVariant = Color(0xFF232A33),
    onSurfaceVariant = Color(0xFFC1C8D2),
    outline = Color(0xFF8B939E),
)

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
)

@Composable
fun MeterTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        shapes = AppShapes,
        content = content,
    )
}
