package com.hexadecinull.vineos.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.hexadecinull.vineos.domain.RootlessAndroid9QemuEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Honest Android 9 early boot console, not a rendered Android desktop. */
@Composable
fun Android9QemuBootTestScreen(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val engine = remember { RootlessAndroid9QemuEngine(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf("Select your Redroid 9 .vrom from Downloads") }
    var console by remember { mutableStateOf("No boot log yet") }
    var stderr by remember { mutableStateOf("") }
    var staging by remember { mutableStateOf(false) }
    var launched by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null && !staging) {
            scope.launch {
                staging = true
                state = "Copying and validating Android 9 ROM. Extra space is required for a ~2 GB disk."
                val result = engine.importVromAndStart(uri)
                if (result.isSuccess) {
                    launched = true
                    state = "QEMU launched. Waiting for the real Android 9 init stages..."
                } else {
                    state = "ROM or VM error: ${result.exceptionOrNull()?.message}"
                }
                staging = false
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { engine.stop() }
    }
    LaunchedEffect(Unit) {
        while (true) {
            if (launched) {
                runCatching {
                    console = engine.log()
                    stderr = engine.stderr()
                    state = if (engine.running()) engine.milestone()
                    else "QEMU process stopped; read serial console and stderr below"
                }
            }
            delay(800)
        }
    }

    Column(
        modifier = modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Android 9 Pie — QEMU early boot test", style = MaterialTheme.typography.titleLarge)
        Text(
            "This mode starts the REAL Android 9 /init from the selected Redroid .vrom " +
                "using rootless QEMU ARM64. The Android GUI, Magisk ROOT for apps and " +
                "Standoff 2 do NOT work yet. This screen shows boot logs only.",
            style = MaterialTheme.typography.bodyMedium,
        )
        Text("QEMU ARM64: " + if (engine.binaryAvailable()) "Bundled" else "Missing")
        Text("Status: $state")
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                enabled = engine.binaryAvailable() && !staging && !engine.running(),
                onClick = { picker.launch(arrayOf("*/*")) },
            ) { Text("Select Android 9 .vrom & boot") }
            OutlinedButton(
                enabled = engine.running(),
                onClick = {
                    engine.stop()
                    launched = false
                    state = "Stopped by user"
                }
            ) { Text("Stop") }
        }
        if (staging) CircularProgressIndicator()
        OutlinedButton(onClick = onBack) { Text("Back to Settings") }
        HorizontalDivider()
        Text("Android 9 serial console", style = MaterialTheme.typography.titleMedium)
        Text(
            console.takeLast(16000),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        HorizontalDivider()
        Text("QEMU stderr", style = MaterialTheme.typography.titleMedium)
        Text(
            stderr.takeLast(3500),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
    }
}
