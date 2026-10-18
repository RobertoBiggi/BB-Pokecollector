package com.robertobiggi.cardcollection.ui.common

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.robertobiggi.cardcollection.work.JobState

/**
 * Fascia con l'avanzamento di un lavoro in background (catalogo o prezzi).
 * @param title String - nome del lavoro.
 * @param state JobState? - stato corrente; se non è in corso la fascia non viene mostrata.
 * @return Unit
 */
@Composable
fun JobBanner(title: String, state: JobState?) {
    if (state == null || !state.running) return
    Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                "$title: ${state.message ?: "avvio…"}",
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (state.total > 0) {
                LinearProgressIndicator(
                    progress = { (state.done.toFloat() / state.total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
            }
        }
    }
}
