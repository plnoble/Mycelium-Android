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
import androidx.compose.ui.res.stringResource
import com.xavierxia.mycelium.R
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
                    text = stringResource(R.string.home_subtitle),
                    style = MaterialTheme.typography.headlineMedium
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.home_phase),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(Modifier.height(16.dp))
                Text("版本 ${BuildConfig.VERSION_NAME}（${BuildConfig.VERSION_CODE}）")
                Text(stringResource(R.string.home_desc))

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
                        Text(stringResource(R.string.home_check_updates))
                    }
                }

                updateState?.let { result ->
                    Spacer(Modifier.height(16.dp))
                    when (result) {
                        is UpdateResult.UpToDate ->
                            Text(stringResource(R.string.update_latest))
                        is UpdateResult.Available -> {
                            Text(stringResource(R.string.update_new_version, result.release.versionName))
                            Spacer(Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth()) {
                                Button(onClick = {
                                    openUrl(context, result.release.apkDownloadUrl)
                                }) {
                                    Text(stringResource(R.string.update_open_apk))
                                }
                            }
                        }
                        is UpdateResult.NoPublishedRelease ->
                            Text(stringResource(R.string.update_none_published))
                        is UpdateResult.Error ->
                            Text(stringResource(R.string.update_failed, result.message))
                    }
                }
            }
        }
    }
}

private fun openUrl(context: Context, url: String) {
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
}
