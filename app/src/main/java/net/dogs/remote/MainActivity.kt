package net.dogs.remote

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import net.dogs.remote.ui.LogScreen
import net.dogs.remote.ui.MagicScreen
import net.dogs.remote.ui.ManualScreen
import net.dogs.remote.ui.ProfilesScreen
import net.dogs.remote.ui.RemoteViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}

private enum class Tab(val title: String) {
    Magic("Magic"),
    Manual("Manual"),
    Profiles("Profiles"),
    Log("Log"),
}

@Composable
private fun App(vm: RemoteViewModel = viewModel()) {
    MaterialTheme {
        if (!vm.sender.hasEmitter) {
            NoIrScreen()
        } else {
            AppTabs(vm)
        }
    }
}

@Composable
private fun AppTabs(vm: RemoteViewModel) {
        var tab by remember { mutableStateOf(Tab.Magic) }
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.values().forEach { t ->
                        NavigationBarItem(
                            selected = tab == t,
                            onClick = { tab = t },
                            icon = {},
                            label = { Text(t.title) },
                        )
                    }
                }
            },
        ) { pad ->
            Box(Modifier.padding(pad)) {
                when (tab) {
                    Tab.Magic -> MagicScreen(vm)
                    Tab.Manual -> ManualScreen(vm)
                    Tab.Profiles -> ProfilesScreen(vm, onUse = { tab = Tab.Manual })
                    Tab.Log -> LogScreen(vm)
                }
            }
        }
        }
    }
}

@Composable
private fun NoIrScreen() {
    Column(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(64.dp))
        Text("No IR emitter found", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "This device doesn't report an IR blaster, so DOGS Remote can't " +
                "transmit. It needs a phone with a built-in IR emitter " +
                "(like the OnePlus 12R).",
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
