package net.dogs.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Magic Mode: one tap rapidly tries the selected probe signal across every
 * variant, previously-worked variants first (the learned sweep order). The
 * user taps IT WORKED the moment the TV responds, names the venue, and the
 * variant is saved as a profile.
 */
@Composable
fun MagicScreen(vm: RemoteViewModel) {
    var showNameDialog by remember { mutableStateOf(false) }
    var nickname by remember { mutableStateOf("") }
    // Variant that was being probed when IT WORKED was tapped. Captured at
    // tap time: the sweep keeps advancing while the name dialog is open, so
    // the state at Save time can point at a later (wrong) variant.
    var workedVariantId by remember { mutableStateOf<String?>(null) }
    // Probe signal in use at tap time — same capture discipline, so the
    // attempt log marks the signal that was actually transmitted.
    var workedProbeButtonId by remember { mutableStateOf<String?>(null) }
    val state = vm.magicState

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Text("Magic Mode", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "One tap tries the selected probe signal across TV variants, " +
                "previously-worked variants first. When the TV responds, tap IT WORKED.",
            style = MaterialTheme.typography.bodyMedium,
        )
        val unsupported = vm.unsupportedVariantIds
        if (unsupported.isNotEmpty()) {
            val labels = unsupported.mapNotNull { vm.db.variantsById[it]?.label }
            Spacer(Modifier.height(8.dp))
            Text(
                "Carrier warning: this phone's IR emitter may not transmit " +
                    labels.joinToString(", ") +
                    " — those variants can't be probed reliably here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(Modifier.height(32.dp))

        Text(
            "Probe signal",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (id in MAGIC_PROBE_OPTIONS) {
                FilterChip(
                    selected = vm.magicProbeButtonId == id,
                    onClick = { vm.setMagicProbeButtonId(id) },
                    label = { Text(buttonLabel(id)) },
                )
            }
        }
        Spacer(Modifier.height(16.dp))

        when (state) {
            is MagicState.Idle -> {
                Button(
                    onClick = { vm.startMagic() },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) { Text("START MAGIC", style = MaterialTheme.typography.titleLarge) }
                // Reset the learned sweep order: an accidental IT WORKED tap
                // steers every future sweep, and the audit log must stay
                // intact — this clears only the order's memory of it.
                if (vm.hasLearnedOrder) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { vm.resetLearnedOrder() }) {
                        Text("Reset learned order")
                    }
                }
            }
            is MagicState.Running -> {
                val label = vm.db.variantsById[state.currentVariantId]?.label
                    ?: state.currentVariantId
                Text(
                    "Trying ${state.done + 1} of ${state.total}",
                    style = MaterialTheme.typography.titleMedium,
                )
                Spacer(Modifier.height(8.dp))
                Text(label, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(24.dp))
                Button(
                    onClick = {
                        workedVariantId =
                            (vm.magicState as? MagicState.Running)?.currentVariantId
                        workedProbeButtonId = vm.magicProbeButtonId
                        showNameDialog = true
                    },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) { Text("IT WORKED", style = MaterialTheme.typography.titleLarge) }
                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = { vm.stopMagic() },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Stop") }
            }
            is MagicState.Done -> {
                Text("No luck this pass.", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(8.dp))
                Text("Try Manual Mode to pick a brand and variant directly.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = { vm.startMagic() }) { Text("TRY AGAIN") }
            }
            is MagicState.Saved -> {
                val label = vm.db.variantsById[state.variantId]?.label ?: state.variantId
                Text("Saved!", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text("Variant \"$label\" is now a profile. Open it from Profiles any time.")
                Spacer(Modifier.height(16.dp))
                Button(onClick = { vm.startMagic() }) { Text("RUN MAGIC AGAIN") }
            }
        }

        if (showNameDialog) {
            val label = workedVariantId?.let { vm.db.variantsById[it]?.label } ?: ""
            val probeLabel = workedProbeButtonId?.let { buttonLabel(it) } ?: ""
            AlertDialog(
                onDismissRequest = {
                    workedVariantId = null
                    workedProbeButtonId = null
                    showNameDialog = false
                },
                title = { Text("It worked!") },
                text = {
                    Column {
                        Text("Saving variant \"$label\" (probed with $probeLabel). Name this TV / venue:")
                        Spacer(Modifier.height(8.dp))
                        TextField(
                            value = nickname,
                            onValueChange = { nickname = it },
                            placeholder = { Text(label) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = {
                        workedVariantId?.let {
                            vm.magicWorked(
                                nickname.trim(),
                                it,
                                workedProbeButtonId ?: MAGIC_PROBE_DEFAULT,
                            )
                        }
                        nickname = ""
                        workedVariantId = null
                        workedProbeButtonId = null
                        showNameDialog = false
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = {
                        workedVariantId = null
                        workedProbeButtonId = null
                        showNameDialog = false
                    }) { Text("Cancel") }
                },
            )
        }
    }
}
