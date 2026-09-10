package net.dogs.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val TIME_FMT = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)

/**
 * Attempt-analysis log: every Magic Mode probe, manual transmit, and blast
 * is recorded here with its variant, button, and whether it worked.
 */
@Composable
fun LogScreen(vm: RemoteViewModel) {
    // Two-step clear: the log is the audit trail AND the learning source for
    // Magic Mode's sweep order — one mis-tap must never wipe it directly.
    var showClearConfirm by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Attempt Log", style = MaterialTheme.typography.headlineMedium)
            OutlinedButton(onClick = { showClearConfirm = true }) { Text("Clear") }
        }
        Text(
            "${vm.attempts.size} attempts recorded. " +
                "\"WORKED\" marks the variant that responded in Magic Mode.",
            style = MaterialTheme.typography.bodySmall,
        )
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(vm.attempts) { a ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        TIME_FMT.format(Date(a.ts)),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1.2f),
                    )
                    Column(modifier = Modifier.weight(2f)) {
                        Text(a.variantLabel, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            buttonLabel(a.button),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Text(
                        if (a.worked) "WORKED" else "sent",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (a.worked) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showClearConfirm) {
        AlertDialog(
            onDismissRequest = { showClearConfirm = false },
            title = { Text("Clear attempt log?") },
            text = {
                Text(
                    "This permanently deletes all ${vm.attempts.size} recorded " +
                        "attempts, including which variants were marked WORKED " +
                        "— Magic Mode's learned sweep order starts over too.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearLog()
                    showClearConfirm = false
                }) { Text("Clear") }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirm = false }) { Text("Cancel") }
            },
        )
    }
}
