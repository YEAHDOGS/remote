package net.dogs.remote.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.gestures.detectPressGestures
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import net.dogs.remote.ir.IrBrand
import net.dogs.remote.ir.IrVariant

/**
 * Manual Mode: pick brand -> variant, get the full remote pad.
 * Blast mode sends the tapped button across every variant (rate-limited,
 * cancellable) — the manual answer to "try this button everywhere".
 */
@Composable
fun ManualScreen(vm: RemoteViewModel) {
    val db = vm.db
    val variant = db.variantsById[vm.manualVariantId] ?: db.magicVariants.firstOrNull()
    val brandOf: (IrVariant?) -> IrBrand? = { v ->
        db.brands.find { b -> b.variants.any { it.id == v?.id } }
    }
    var brand by remember(variant?.id) { mutableStateOf(brandOf(variant)) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Manual Mode", style = MaterialTheme.typography.headlineMedium)

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            DropDown(
                label = brand?.name ?: "Brand",
                options = db.brands.map { it.name },
                onPick = { name ->
                    val b = db.brands.find { it.name == name }
                    brand = b
                    vm.manualVariantId = b?.variants?.firstOrNull()?.id
                },
                modifier = Modifier.weight(1f),
            )
            DropDown(
                label = variant?.label ?: "Variant",
                options = (brand ?: brandOf(variant))?.variants?.map { it.label } ?: emptyList(),
                onPick = { label ->
                    val v = (brand ?: brandOf(variant))?.variants?.find { it.label == label }
                    if (v != null) {
                        brand = brandOf(v)
                        vm.manualVariantId = v.id
                    }
                },
                modifier = Modifier.weight(1f),
            )
        }

        if (variant != null && !variant.verified) {
            Text(
                "Unverified timings for this variant — confirm on the device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        if (variant != null && vm.unsupportedVariantIds.contains(variant.id)) {
            val freq = variant.buttons.values.firstOrNull()?.freqHz
            Text(
                "Carrier warning: this phone's IR emitter may not support " +
                    (if (freq != null) "${freq / 1000}kHz" else "this variant's carrier") +
                    " — buttons may not work on this device.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(checked = vm.blastMode, onCheckedChange = { vm.blastMode = it })
            Text("Blast across all variants")
        }

        val blast = vm.blastState
        if (blast is BlastState.Running) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text("Blasting ${buttonLabel(blast.buttonId)}: ${blast.done + 1}/${blast.total}")
                OutlinedButton(onClick = { vm.stopBlast() }) { Text("Stop") }
            }
        }

        if (variant != null) {
            val ids = BUTTON_ORDER.filter { variant.buttons.containsKey(it) }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(ids) { id ->
                    val repeatable = REPEATABLE_BUTTONS.contains(id) && !vm.blastMode
                    Button(
                        onClick = { if (!repeatable) vm.sendButton(variant.id, id) },
                        modifier = Modifier
                            .height(56.dp)
                            .then(
                                if (repeatable) {
                                    Modifier.pointerInput(variant.id, id, vm.blastMode) {
                                        detectPressGestures(
                                            onPress = {
                                                vm.startRepeat(variant.id, id)
                                                try {
                                                    tryAwaitRelease()
                                                } finally {
                                                    vm.stopRepeat()
                                                }
                                            },
                                        )
                                    }
                                } else {
                                    Modifier
                                },
                            ),
                    ) { Text(buttonLabel(id)) }
                }
            }
        } else {
            Text("No variant selected.")
        }
    }
}

@Composable
private fun DropDown(
    label: String,
    options: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
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
                    text = { Text(o) },
                    onClick = {
                        expanded = false
                        onPick(o)
                    },
                )
            }
        }
    }
}
