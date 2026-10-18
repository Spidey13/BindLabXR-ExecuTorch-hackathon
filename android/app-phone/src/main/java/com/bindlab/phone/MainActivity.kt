package com.bindlab.phone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bindlab.shared.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    private val vm: PhoneViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var tab by rememberSaveable { mutableIntStateOf(0) }
                val connected by vm.ws.connected.collectAsState()
                val haptic by vm.lastHaptic.collectAsState()
                Scaffold(bottomBar = {
                    NavigationBar {
                        listOf(
                            "Scan" to Icons.Filled.Search, "Ladder" to Icons.AutoMirrored.Filled.List,
                            "Binding" to Icons.Filled.Favorite, "Settings" to Icons.Filled.Settings,
                        ).forEachIndexed { i, (label, icon) ->
                            NavigationBarItem(selected = tab == i, onClick = { tab = i; if (i == 1) vm.refreshLadder() },
                                icon = { Icon(icon, label) }, label = { Text(label) })
                        }
                    }
                }) { pad ->
                    Column(Modifier.padding(pad).fillMaxSize()) {
                        Text(
                            (if (connected) "● server connected" else "○ server offline (${vm.config.server})") +
                                (haptic?.let { "  · last haptic: $it" } ?: ""),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        val m = Modifier.fillMaxSize()
                        when (tab) {
                            0 -> QrScanScreen(vm, m)
                            1 -> LadderScreen(vm, m)
                            2 -> BindingScreen(vm, m)
                            else -> SettingsScreen(vm.config, vm.modelDir, vm::onServerChanged, m)
                        }
                    }
                }
            }
        }
    }
}
