package com.robertobiggi.cardcollection.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Palette della demo: grigio Acciaio e rosa Folletto, come i tipi di Mawile.
private val Light = lightColorScheme(
    primary = Color(0xFFB0245D),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFF6DFE8),
    onPrimaryContainer = Color(0xFF3A0A1E),
    secondary = Color(0xFF64748F),
    background = Color(0xFFECEFF4),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFE2E6EE),
    onSurface = Color(0xFF181C26),
    onSurfaceVariant = Color(0xFF5A6274),
    outline = Color(0xFFD2D7E1),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFEF6D9F),
    onPrimary = Color(0xFF1B0912),
    primaryContainer = Color(0xFF3A1D2A),
    onPrimaryContainer = Color(0xFFF6DFE8),
    secondary = Color(0xFF8E9CB6),
    background = Color(0xFF0F1218),
    surface = Color(0xFF181D27),
    surfaceVariant = Color(0xFF222937),
    onSurface = Color(0xFFE7EAF1),
    onSurfaceVariant = Color(0xFF98A1B4),
    outline = Color(0xFF2C3442),
)

/** Colori semantici per stato (posseduta, affidabilità). */
object StatusColors {
    val ok = Color(0xFF1D7F55)
    val warn = Color(0xFF9A6512)
    val bad = Color(0xFFB8372B)
}

/** Stile monospaziato per codici e prezzi. */
val MonoStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp)

/**
 * Tema dell'app, chiaro o scuro secondo il sistema.
 * @param content @Composable () -> Unit - contenuto da tematizzare.
 * @return Unit
 */
@Composable
fun CardCollectionTheme(content: @Composable () -> Unit) {
    val base = Typography()
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = base.copy(
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.ExtraBold),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
        ),
        content = content,
    )
}
