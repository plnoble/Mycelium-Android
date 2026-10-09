package com.xavierxia.mycelium.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.xavierxia.mycelium.BuildConfig
import com.xavierxia.mycelium.update.UpdateManager
import com.xavierxia.mycelium.update.UpdateResult
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyceliumApp(updateManager: UpdateManager = remember { UpdateManager() }) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var updateState by remember { mutableStateOf<UpdateResult?>(null) }
    var checking by remember { mutableStateOf(false) }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("知衍") }) }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = "Local AI Companion",
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "Phase 0 · Foundation",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(16.dp))
                Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                Text("Character, memory and local inference modules come next.")

                Spacer(Modifier.height(28.dp))

                Button(
                    enabled = !checking,
                    onClick = {
                        scope.launch {
                            checking = true
                            try {
                                updateState = updateManager.checkForUpdate()
                            } finally {
                                checking = false
                            }
                        }
                    }
                ) {
                    if (checking) {
                        CircularProgressIndicator()
                    } else {
                        Text("Check GitHub for updates")
                    }
                }

                updateState?.let { result ->
                    Spacer(Modifier.height(16.dp))
                    when (result) {
                        is UpdateResult.UpToDate ->
                            Text("You are running the latest release.")
                        is UpdateResult.Available -> {
                            Text("New version: ${result.release.versionName}")
                            Spacer(Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Button(onClick = {
                                    openUrl(context, result.release.apkDownloadUrl)
                                }) {
                                    Text("Open release APK")
                                }
                            }
                        }
                        is UpdateResult.NoPublishedRelease ->
                            Text("No GitHub Release has been published yet.")
                        is UpdateResult.Error ->
                            Text("Update check failed: ${result.message}")
                    }
                }
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
