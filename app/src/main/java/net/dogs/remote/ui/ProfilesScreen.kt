package net.dogs.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import net.dogs.remote.data.TvProfile

/**
 * Saved TVs: rename, set default, delete, and jump straight into
 * Manual Mode with the profile's variant ("Use").
 */
@Composable
fun ProfilesScreen(vm: RemoteViewModel, onUse: () -> Unit) {
    var renameTarget by remember { mutableStateOf<TvProfile?>(null) }
    var renameText by remember { mutableStateOf("") }
    var showSaveCurrent by remember { mutableStateOf(false) }
    var saveName by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Profiles", style = MaterialTheme.typography.headlineMedium)

        OutlinedButton(onClick = { showSaveCurrent = true }) {
            Text("Save current selection as profile")
        }

        if (vm.profiles.isEmpty()) {
            Text(
                "No saved TVs yet. Run Magic Mode and tap IT WORKED " +
                    "when a TV responds.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(vm.profiles, key = { it.id }) { p ->
                val label = vm.db.variantsById[p.variantId]?.label ?: p.variantId
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row {
                        Text(
                            p.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        if (p.isDefault) {
                            Text(
                                "DEFAULT",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                    Text(label, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            vm.manualVariantId = p.variantId
                            onUse()
                        }) { Text("Use") }
                        OutlinedButton(onClick = {
                            renameTarget = p
                            renameText = p.name
                        }) { Text("Rename") }
                        if (!p.isDefault) {
                            OutlinedButton(onClick = { vm.setDefaultProfile(p.id) }) {
                                Text("Default")
                            }
                        }
                        OutlinedButton(onClick = { vm.deleteProfile(p.id) }) {
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }

    if (renameTarget != null) {
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("Rename profile") },
            text = {
                TextField(
                    value = renameText,
                    onValueChange = { renameText = it },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = renameTarget?.name?.trim()
                    if (!renameText.isBlank() && renameText.trim() != t) {
                        vm.renameProfile(renameTarget!!.id, renameText.trim())
                    }
                    renameTarget = null
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { renameTarget = null }) { Text("Cancel") }
            },
        )
    }

    if (showSaveCurrent) {
        AlertDialog(
            onDismissRequest = { showSaveCurrent = false },
            title = { Text("Save current selection") },
            text = {
                TextField(
                    value = saveName,
                    onValueChange = { saveName = it },
                    placeholder = { Text("Venue nickname") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val vid = vm.manualVariantId
                    if (saveName.isNotBlank() && vid != null) {
                        vm.addProfile(saveName.trim(), vid)
                    }
                    saveName = ""
                    showSaveCurrent = false
                }) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveCurrent = false }) { Text("Cancel") }
            },
        )
    }
}
