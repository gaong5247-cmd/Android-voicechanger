package dev.voiceshift

import android.Manifest
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*

class MainActivity : ComponentActivity() {
    private var report by mutableStateOf("No diagnostics run yet.")
    private var shizuku by mutableStateOf("Checking…")
    private var busy by mutableStateOf(false)
    private var task: Job? = null
    private val readiness = EngineReadiness()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) runTest() else report = "Microphone permission denied. No audio captured."
    }
    private fun runTest() {
        if (busy) return
        busy = true
        task = lifecycleScope.launch {
            try {
                report = "Sending coded PCM to an existing HAL and checking Android capture."
                report = withContext(Dispatchers.IO) {
                    val mic = CaptureTest.run(MediaRecorder.AudioSource.MIC)
                    val comm = CaptureTest.run(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
                    "$mic\n\n$comm"
                }
            } catch (e: CancellationException) { report = "Test stopped. Results unverified."; throw e }
            catch (e: LinkageError) { report = "Native engine could not load: ${e.message}" }
            catch (e: Exception) { report = "Test failed: ${e.javaClass.simpleName}: ${e.message}" }
            finally { busy = false }
        }
    }
    private fun checkBackends() {
        if (busy) return
        busy = true
        task = lifecycleScope.launch {
            try {
                report = withContext(Dispatchers.IO) {
                    listOf(StandardBackend(), ShizukuBackend(this@MainActivity), RootBackend())
                        .joinToString("\n\n") { it.probe().let { c -> "${c.name}\n${c.reason}" } }
                }
            } catch (e: Exception) { report = "Backend check failed: ${e.message}" }
            finally { busy = false }
        }
    }
    override fun onStop() { task?.cancel(); super.onStop() }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            shizuku = withContext(Dispatchers.IO) {
                if (runCatching { packageManager.getPackageInfo("moe.shizuku.privileged.api", 0) }.isSuccess)
                    "Installed · connection unverified" else "Not installed"
            }
        }
        setContent {
            val dark = isSystemInDarkTheme()
            val colors = when {
                Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(this)
                Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(this)
                dark -> darkColorScheme()
                else -> lightColorScheme()
            }
            MaterialTheme(colorScheme = colors) {
                var diagnostics by rememberSaveable { mutableStateOf(false) }
                val snackbar = remember { SnackbarHostState() }
                val scope = rememberCoroutineScope()
                Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 24.dp)
                        .verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        Spacer(Modifier.height(12.dp))
                        Text("VoiceShift", style = MaterialTheme.typography.headlineLarge)
                        Text(if (diagnostics) "Diagnostics" else "Zero-shot Voice Changer", style = MaterialTheme.typography.bodyLarge)
                        if (!diagnostics) {
                            Card(Modifier.fillMaxWidth()) {
                                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                                    StatusRow("Engine", "MeanVC2")
                                    StatusRow("Model", "Not Installed")
                                    StatusRow("Backend", "CPU · engine pending")
                                    StatusRow("Virtual Mic", "Not Ready")
                                    StatusRow("Monitor", "Not Ready")
                                    StatusRow("Shizuku", shizuku)
                                }
                            }
                            Text("Voice engine not ready", style = MaterialTheme.typography.titleMedium)
                            Text("Model installation and Android inference are the next development stage. System microphone routing has not been verified.", style = MaterialTheme.typography.bodyMedium)
                            OutlinedButton(onClick = { scope.launch { snackbar.showSnackbar("Model import is not available in this first CI build.") } }, modifier = Modifier.fillMaxWidth()) { Text("Install / Import Model") }
                            FilledTonalButton(onClick = { diagnostics = true }, modifier = Modifier.fillMaxWidth()) { Text("Diagnostics") }
                            Button(onClick = {}, enabled = readiness.canStartConversion, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("START") }
                            Text("Monitor, Shizuku connectivity and system virtual microphone are independent capabilities.", style = MaterialTheme.typography.bodySmall)
                        } else {
                            Card(Modifier.fillMaxWidth()) { Text(report, Modifier.padding(20.dp)) }
                            Button(onClick = { checkBackends() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Check backend requirements") }
                            OutlinedButton(onClick = {
                                if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) runTest()
                                else permission.launch(Manifest.permission.RECORD_AUDIO)
                            }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (busy) "Testing…" else "Test existing HAL PCM input") }
                            OutlinedButton(onClick = { task?.cancel() }, enabled = busy, modifier = Modifier.fillMaxWidth()) { Text("STOP TEST") }
                            Text("The PCM test requires a compatible preinstalled development HAL. A passed signal test does not prove MeanVC2 conversion or third-party app support.", style = MaterialTheme.typography.bodySmall)
                            TextButton(onClick = { task?.cancel(); diagnostics = false }) { Text("Back to Home") }
                        }
                        Spacer(Modifier.height(24.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(start = 12.dp))
    }
}
