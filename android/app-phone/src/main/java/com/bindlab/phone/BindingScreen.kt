package com.bindlab.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

@Composable
fun BindingScreen(vm: PhoneViewModel, modifier: Modifier = Modifier) {
    val ui by vm.binding.collectAsState()
    val rows by vm.ladder.collectAsState()
    val target by vm.target.collectAsState()
    val binder by vm.binder.collectAsState()
    val runId by vm.runId.collectAsState()
    val runState by vm.runState.collectAsState()
    var size by rememberSaveable { mutableStateOf("8M") }
    var backend by rememberSaveable { mutableStateOf("qnn") }
    val available = rows.firstOrNull { it.size == size && it.backend == backend }?.available == true

    Column(modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Binding", style = MaterialTheme.typography.headlineSmall)
        Text("Target ${target.length} aa · binder ${binder.length} aa", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EsmModels.SIZES.forEach { s -> FilterChip(selected = s == size, onClick = { size = s }, label = { Text(s) }) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            EsmModels.BACKENDS.forEach { b -> FilterChip(selected = b == backend, onClick = { backend = b }, label = { Text(b) }) }
        }
        if (!available) {
            Text("model missing: ${EsmModels.base(size, backend)}.pte in ${vm.modelDir}", color = MaterialTheme.colorScheme.error)
        }
        Button(onClick = { vm.runBinding(size, backend) }, enabled = !ui.running && available) {
            Text(if (ui.running) "Scoring…" else "Embed + score")
        }
        if (ui.message.isNotEmpty()) Text(ui.message, style = MaterialTheme.typography.bodyMedium)
        ui.score?.let { s ->
            Card(
                colors = CardDefaults.cardColors(containerColor = if (s.bound) Color(0xFFDFF5E1) else Color(0xFFFFE0E0)),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(if (s.bound) "Binding Successful" else "No binding", fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge)
                    Text("score %.3f (threshold %.2f, %s)".format(s.value, s.threshold, s.method))
                    ui.latencyMs?.let { Text("embed latency %.1f ms (2 sequences)".format(it)) }
                    if (ui.targetResidues.isNotEmpty()) Text("target interface: ${ui.targetResidues.joinToString()}")
                    if (ui.binderResidues.isNotEmpty()) Text("binder interface: ${ui.binderResidues.joinToString()}")
                }
            }
        }
        runId?.let { Text("run $it · ${runState ?: ""}", style = MaterialTheme.typography.bodySmall) }
    }
}
