package net.dogs.remote.ui

/** Human labels and remote-pad order for the normalized button ids. */
val BUTTON_LABELS: Map<String, String> = mapOf(
    "power" to "Power",
    "vol_up" to "Vol +",
    "vol_down" to "Vol \u2212",
    "mute" to "Mute",
    "ch_up" to "Ch +",
    "ch_down" to "Ch \u2212",
    "input" to "Input",
    "up" to "\u25B2",
    "down" to "\u25BC",
    "left" to "\u25C0",
    "right" to "\u25B6",
    "ok" to "OK",
    "menu" to "Menu",
    "exit" to "Exit",
    "guide" to "Guide",
)

val BUTTON_ORDER: List<String> = listOf(
    "power", "mute", "input",
    "vol_up", "vol_down", "ch_up", "ch_down",
    "up", "left", "ok", "right", "down",
    "menu", "guide", "exit",
)

fun buttonLabel(id: String): String = BUTTON_LABELS[id] ?: id

/** Keys where holding the button down repeats the transmit (volume / channel). */
val REPEATABLE_BUTTONS: Set<String> = setOf("vol_up", "vol_down", "ch_up", "ch_down")
