package net.dogs.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
 * Magic Mode: one tap rapidly tries volume-up across every variant,
 * Samsung first. The user taps IT WORKED the moment the TV responds,
 * names the venue, and the variant is saved as a profile.
 */
@Composable
fun MagicScreen(vm: RemoteViewModel) {
    var showNameDialog by remember { mutableStateOf(false) }
    var nickname by remember { mutableStateOf("") }
    val state = vm.magicState

    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top,
    ) {
        Text("Magic Mode", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "One tap tries volume-up across TV variants, Samsung first. " +
                "When the TV responds, tap IT WORKED.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(32.dp))

        when (state) {
            is MagicState.Idle -> {
                Button(
                    onClick = { vm.startMagic() },
                    modifier = Modifier.fillMaxWidth().height(64.dp),
                ) { Text("START MAGIC", style = MaterialTheme.typography.titleLarge) }
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
                    onClick = { showNameDialog = true },
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
            val running = state as? MagicState.Running
            val label = running?.let { vm.db.variantsById[it.currentVariantId]?.label } ?: ""
            AlertDialog(
                onDismissRequest = { showNameDialog = false },
                title = { Text("It worked!") },
                text = {
                    Column {
                        Text("Saving variant \"$label\". Name this TV / venue:")
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
                        vm.magicWorked(nickname.trim())
                        nickname = ""
                        showNameDialog = false
                    }) { Text("Save") }
                },
                dismissButton = {
                    TextButton(onClick = { showNameDialog = false }) { Text("Cancel") }
                },
            )
        }
    }
}
