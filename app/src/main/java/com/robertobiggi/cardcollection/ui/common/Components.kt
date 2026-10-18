package com.robertobiggi.cardcollection.ui.common

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.robertobiggi.cardcollection.core.model.Confidence
import com.robertobiggi.cardcollection.data.repo.CardUi
import com.robertobiggi.cardcollection.ui.theme.MonoStyle
import com.robertobiggi.cardcollection.ui.theme.StatusColors
import java.io.File

/**
 * Immagine della carta letta dal file locale; se manca mostra un segnaposto con nome e codice.
 * @param card CardUi - carta da mostrare.
 * @param path String? - percorso del file locale.
 * @param modifier Modifier - modificatore di layout.
 * @return Unit
 */
@Composable
fun CardImage(card: CardUi, path: String?, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.aspectRatio(63f / 88f).clip(shape)) {
        if (path != null && File(path).isFile) {
            AsyncImage(
                model = File(path),
                contentDescription = "${card.card.name} ${card.printedCode}",
                contentScale = ContentScale.Fit,
                modifier = Modifier.matchParentSizeCompat(),
            )
        } else {
            Placeholder(card)
        }
    }
}

/**
 * Riempie il contenitore (equivalente di matchParentSize per un figlio singolo).
 * @receiver Modifier - modificatore di partenza.
 * @return Modifier - modificatore che occupa tutto lo spazio disponibile.
 */
private fun Modifier.matchParentSizeCompat(): Modifier = this.fillMaxWidth().aspectRatio(63f / 88f)

/**
 * Segnaposto per le carte senza immagine (molte carte giapponesi su TCGdex non ne hanno).
 * @param card CardUi - carta da rappresentare.
 * @return Unit
 */
@Composable
private fun Placeholder(card: CardUi) {
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(63f / 88f)
            .background(Brush.linearGradient(listOf(Color(0xFFB9C2D3), Color(0xFF7E8AA3))))
            .border(4.dp, Color(0xFFE9CF63), RoundedCornerShape(8.dp))
            .padding(10.dp),
    ) {
        Text(
            card.card.name,
            color = Color(0xFF1D1F26),
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.TopStart),
        )
        Text(
            "Immagine non disponibile",
            color = Color(0xFF1D1F26),
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.align(Alignment.Center),
        )
        Text(card.printedCode, color = Color(0xFF1D1F26), style = MonoStyle, modifier = Modifier.align(Alignment.BottomStart))
    }
}

/**
 * Badge "possedute/slot" con colore di stato.
 * @param owned Int - slot posseduti.
 * @param slots Int - slot desiderati.
 * @return Unit
 */
@Composable
fun SlotBadge(owned: Int, slots: Int) {
    val complete = slots > 0 && owned >= slots
    val (bg, fg) = when {
        complete -> StatusColors.ok to Color.White
        owned > 0 -> MaterialTheme.colorScheme.primaryContainer to MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(color = bg, shape = RoundedCornerShape(50)) {
        Text(
            "$owned/$slots",
            color = fg,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

/**
 * Etichetta dell'affidabilità di un prezzo.
 * @param confidence String - nome del valore [Confidence].
 * @param stale Boolean - true se il prezzo è vecchio.
 * @return Unit
 */
@Composable
fun ConfidenceChip(confidence: String, stale: Boolean) {
    val value = Confidence.entries.firstOrNull { it.name == confidence }
    val (label, color) = when {
        stale -> "vecchio" to StatusColors.bad
        value == Confidence.HIGH -> "affidabile" to StatusColors.ok
        value == Confidence.MEDIUM -> "variante incerta" to StatusColors.warn
        value == Confidence.MANUAL -> "manuale" to MaterialTheme.colorScheme.secondary
        else -> "indicativo" to StatusColors.bad
    }
    Surface(shape = RoundedCornerShape(50), color = Color.Transparent, border = androidx.compose.foundation.BorderStroke(1.dp, color)) {
        Text(label, color = color, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp))
    }
}

/**
 * Piccolo grafico dell'andamento del prezzo.
 * @param values List<Double> - prezzi dal più vecchio al più recente.
 * @param modifier Modifier - dimensioni del grafico.
 * @return Unit
 */
@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier) {
    if (values.size < 2) return
    val color = MaterialTheme.colorScheme.primary
    Canvas(modifier) {
        val min = values.min()
        val span = (values.max() - min).takeIf { it > 0 } ?: 1.0
        val stepX = size.width / (values.size - 1)
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            val y = size.height - ((v - min) / span * size.height).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
        val lastY = size.height - ((values.last() - min) / span * size.height).toFloat()
        drawCircle(color, radius = 3.dp.toPx(), center = Offset(size.width, lastY))
    }
}

/**
 * Riga etichetta-valore per i metadati.
 * @param label String - etichetta.
 * @param value String - valore.
 * @param mono Boolean - true per usare il font monospaziato.
 * @return Unit
 */
@Composable
fun MetaRow(label: String, value: String, mono: Boolean = false) {
    Column(Modifier.padding(vertical = 2.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = if (mono) MonoStyle else MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
    }
}
