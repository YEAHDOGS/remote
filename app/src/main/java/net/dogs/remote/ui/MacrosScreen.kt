package net.dogs.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import net.dogs.remote.data.MacroStep
import net.dogs.remote.data.MacroStore
import net.dogs.remote.ir.IrVariant

/**
 * Macros: named button sequences for one TV variant ("Movie night" =
 * power + input + vol dance), played back with per-step pauses. Steps are
 * capped at [MacroStore.MAX_STEPS], pauses clamped to
 * [MacroStore.STEP_DELAY_MIN_MS]..[MacroStore.STEP_DELAY_MAX_MS], playback is
 * a single cancellable job, and stale button ids are skipped at playback —
 * so a macro can never run away, hang the emitter, or send a button the
 * current database doesn't know.
 */
@Composable
fun MacrosScreen(vm: RemoteViewModel) {
    var editor by remember { mutableStateOf<MacroEditorState?>(null) }
    var saveError by remember { mutableStateOf(false) }
    val running = vm.macroState as? MacroState.Running

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Macros", style = MaterialTheme.typography.headlineMedium)
        Text(
            "One tap fires a whole sequence — power, input, volume. " +
                "Pauses between steps are capped at " +
                "${MacroStore.STEP_DELAY_MAX_MS / 1000}s.",
            style = MaterialTheme.typography.bodyMedium,
        )

        Button(
            onClick = {
                val v = vm.manualVariantId?.let { vm.db.variantsById[it] }
                    ?: vm.db.magicVariants.firstOrNull()
                editor = MacroEditorState(null, "", v, mutableListOf())
                saveError = false
            },
        ) { Text("New macro") }

        if (saveError) {
            Text(
                "Couldn't save — check the name (1-${MacroStore.NAME_MAX_LEN} chars), " +
                    "at least one step, or the ${MacroStore.MAX_MACROS}-macro limit.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(vm.macros, key = { it.id }) { m ->
                val label = vm.db.variantsById[m.variantId]?.label ?: m.variantId
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            m.name,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${m.steps.size} steps · $label",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (running?.macroId == m.id) {
                        Text(
                            "Running ${running.done}/${running.total} — tap Stop to cancel",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (running?.macroId == m.id) {
                            Button(onClick = { vm.stopMacro() }) { Text("Stop") }
                        } else {
                            Button(
                                onClick = { vm.runMacro(m.id) },
                                enabled = running == null,
                            ) { Text("Run") }
                        }
                        OutlinedButton(onClick = {
                            editor = MacroEditorState(
                                id = m.id,
                                name = m.name,
                                variant = vm.db.variantsById[m.variantId],
                                steps = m.steps.map { it.toEditorStep() }.toMutableList(),
                            )
                            saveError = false
                        }) { Text("Edit") }
                        OutlinedButton(onClick = { vm.deleteMacro(m.id) }) { Text("Delete") }
                    }
                }
            }
        }
    }

    val e = editor
    if (e != null) {
        MacroEditorDialog(
            vm = vm,
            editor = e,
            onDismiss = { editor = null },
            onSave = { ok ->
                if (ok) {
                    editor = null
                } else {
                    saveError = true
                }
            },
        )
    }
}

private data class EditorStep(var buttonId: String, var delayText: String)

private fun MacroStep.toEditorStep() =
    EditorStep(buttonId, delayAfterMs.toString())

private data class MacroEditorState(
    val id: String?,
    val name: String,
    val variant: IrVariant?,
    val steps: MutableList<EditorStep>,
)

@Composable
private fun MacroEditorDialog(
    vm: RemoteViewModel,
    editor: MacroEditorState,
    onDismiss: () -> Unit,
    onSave: (Boolean) -> Unit,
) {
    val db = vm.db
    var name by remember { mutableStateOf(editor.name) }
    var variant by remember { mutableStateOf(editor.variant ?: db.magicVariants.firstOrNull()) }
    var steps by remember { mutableStateOf(editor.steps.toMutableList()) }
    val buttonIds = variant?.let { v -> BUTTON_ORDER.filter { v.buttons.containsKey(it) } } ?: emptyList()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editor.id == null) "New macro" else "Edit macro") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Macro name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LocalDropDown(
                    label = variant?.label ?: "Variant",
                    options = db.brands.flatMap { it.variants }.map { it.label },
                    onPick = { label ->
                        val v = db.brands.flatMap { it.variants }.find { it.label == label }
                        if (v != null && v.id != variant?.id) {
                            variant = v
                            // Steps belong to the old variant's button set; a
                            // different variant may not have the same buttons.
                            steps = steps.filter {
                                v.buttons.containsKey(it.buttonId)
                            }.toMutableList()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Steps", style = MaterialTheme.typography.titleSmall)
                steps.forEachIndexed { i, step ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        LocalDropDown(
                            label = buttonLabel(step.buttonId),
                            options = buttonIds,
                            onPick = { step.buttonId = it },
                            modifier = Modifier.weight(1f),
                            optionLabel = ::buttonLabel,
                        )
                        TextField(
                            value = step.delayText,
                            onValueChange = { t ->
                                if (t.all { c -> c.isDigit() }) step.delayText = t.take(5)
                            },
                            label = { Text("Pause ms") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            singleLine = true,
                            modifier = Modifier.weight(0.7f),
                        )
                        TextButton(onClick = {
                            steps = steps.toMutableList().also { it.removeAt(i) }
                        }) { Text("✕") }
                    }
                }
                if (steps.size < MacroStore.MAX_STEPS) {
                    OutlinedButton(onClick = {
                        val first = buttonIds.firstOrNull() ?: return@OutlinedButton
                        steps = steps.toMutableList().also {
                            it += EditorStep(first, MacroStore.STEP_DELAY_DEFAULT_MS.toString())
                        }
                    }) { Text("Add step") }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val v = variant ?: return@TextButton
                val macroSteps = steps.map {
                    MacroStep(
                        it.buttonId,
                        it.delayText.toLongOrNull()
                            ?: MacroStore.STEP_DELAY_DEFAULT_MS,
                    )
                }
                val ok = if (editor.id == null) {
                    vm.addMacro(name.trim(), v.id, macroSteps)
                } else {
                    vm.updateMacro(editor.id, name.trim(), v.id, macroSteps)
                }
                onSave(ok)
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun LocalDropDown(
    label: String,
    options: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
    optionLabel: (String) -> String = { it },
) {
    var expanded by remember { mutableStateOf(false) }
    Box(modifier) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text(label, maxLines = 1) }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            options.forEach { o ->
                DropdownMenuItem(
                    text = { Text(optionLabel(o)) },
                    onClick = {
                        expanded = false
                        onPick(o)
                    },
                )
            }
        }
    }
}
