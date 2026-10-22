package de.simon.dankelmann.bluetoothlespam.ui.update

import android.text.format.Formatter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.InstallMobile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.simon.dankelmann.bluetoothlespam.BuildConfig
import de.simon.dankelmann.bluetoothlespam.update.DownloadState
import de.simon.dankelmann.bluetoothlespam.update.DownloadStatus
import de.simon.dankelmann.bluetoothlespam.update.InstallResult
import de.simon.dankelmann.bluetoothlespam.update.UpdateState
import de.simon.dankelmann.bluetoothlespam.update.cancelDownload
import de.simon.dankelmann.bluetoothlespam.update.checkForUpdate
import de.simon.dankelmann.bluetoothlespam.update.downloadUpdate
import de.simon.dankelmann.bluetoothlespam.update.installDownloadedUpdate
import de.simon.dankelmann.bluetoothlespam.update.openInstallPermissionSettings
import de.simon.dankelmann.bluetoothlespam.update.queryDownloadStatus
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val RELEASES_URL =
    "https://github.com/royallorde-maker/Bluetooth-LE-Spam/releases/latest"

/**
 * Self-contained Updates card for the Settings screen. Checks the GitHub-hosted version manifest,
 * downloads the APK through DownloadManager and hands it to the system installer.
 */
@Composable
fun UpdateSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    var state by remember { mutableStateOf<UpdateState>(UpdateState.Idle) }
    var downloadStatus by remember { mutableStateOf<DownloadStatus?>(null) }
    var actionMessage by remember { mutableStateOf<String?>(null) }

    val availableInfo = (state as? UpdateState.Available)?.info

    fun runCheck() {
        scope.launch {
            actionMessage = null
            state = UpdateState.Checking
            state = checkForUpdate(context)
        }
    }

    LaunchedEffect(Unit) {
        if (state == UpdateState.Idle) {
            state = UpdateState.Checking
            state = checkForUpdate(context)
        }
    }

    LaunchedEffect(availableInfo?.versionName) {
        val info = availableInfo ?: return@LaunchedEffect
        downloadStatus = queryDownloadStatus(context, info.versionName)
    }

    LaunchedEffect(downloadStatus?.state, availableInfo?.versionName) {
        val info = availableInfo ?: return@LaunchedEffect
        if (downloadStatus?.state != DownloadState.RUNNING) return@LaunchedEffect
        while (true) {
            delay(500)
            val next = queryDownloadStatus(context, info.versionName)
            downloadStatus = next
            if (next.state != DownloadState.RUNNING) break
        }
    }

    fun startDownload() {
        val info = availableInfo ?: return
        actionMessage = null
        downloadStatus = DownloadStatus(DownloadState.RUNNING)
        if (downloadUpdate(context, info)) {
            downloadStatus = queryDownloadStatus(context, info.versionName)
        } else {
            downloadStatus = DownloadStatus(DownloadState.MISSING)
            actionMessage = "The download could not be started. Please try again."
        }
    }

    fun install() {
        val info = availableInfo ?: return
        actionMessage = null
        when (val result = installDownloadedUpdate(context, info.versionName)) {
            InstallResult.Started -> Unit
            InstallResult.NeedsInstallPermission -> {
                actionMessage =
                    "Allow \"Install unknown apps\" for Bluetooth LE Spam, then tap Install again."
                openInstallPermissionSettings(context)
            }
            InstallResult.NotDownloaded -> {
                downloadStatus = DownloadStatus(DownloadState.MISSING)
                actionMessage = "The update has not been downloaded yet."
            }
            InstallResult.FileMissing -> {
                downloadStatus = DownloadStatus(DownloadState.MISSING)
                actionMessage = "The downloaded file is missing. Please download it again."
            }
            InstallResult.InvalidPackage -> {
                downloadStatus = DownloadStatus(DownloadState.MISSING)
                actionMessage = "The downloaded file is damaged. Please download it again."
            }
            InstallResult.WrongPackage -> {
                actionMessage =
                    "This update does not match this installed build (release vs. debug). " +
                        "Install the release APK from the release page instead."
            }
            InstallResult.SignatureMismatch -> {
                actionMessage =
                    "This update is signed with a different key than the installed app. " +
                        "If you installed the official app, uninstall it first (this removes " +
                        "its data), then install the update again."
            }
            InstallResult.NoInstaller -> {
                actionMessage = "No package installer is available on this device."
            }
            is InstallResult.Failed -> {
                actionMessage = result.message
            }
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.SystemUpdate,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 16.dp)
                        .size(28.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "App Updates",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Installed version ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (state == UpdateState.Checking || state == UpdateState.Idle) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                }
            }

            when (val current = state) {
                UpdateState.Idle -> Text(
                    text = "Waiting to check for updates.",
                    style = MaterialTheme.typography.bodyMedium,
                )

                UpdateState.Checking -> Text(
                    text = "Checking for updates…",
                    style = MaterialTheme.typography.bodyMedium,
                )

                UpdateState.UpToDate -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.CheckCircle,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(18.dp),
                        )
                        Text(
                            text = "You're on the latest version.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    OutlinedButton(onClick = { runCheck() }) {
                        RefreshIcon()
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Check again")
                    }
                }

                UpdateState.Offline -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.CloudOff,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(18.dp),
                        )
                        Text(
                            text = "You're offline. Connect to the internet to check for updates.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    OutlinedButton(onClick = { runCheck() }) {
                        RefreshIcon()
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Retry")
                    }
                }

                is UpdateState.Error -> {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Filled.ErrorOutline,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .size(18.dp),
                        )
                        Text(
                            text = "Couldn't check for updates: ${current.message}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { runCheck() }) {
                            RefreshIcon()
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Try again")
                        }
                        TextButton(onClick = { uriHandler.openUri(RELEASES_URL) }) {
                            Text("Open release page")
                        }
                    }
                }

                is UpdateState.Available -> {
                    val info = current.info
                    Text(
                        text = if (info.force) {
                            "Version ${info.versionName} is required"
                        } else {
                            "Version ${info.versionName} is available"
                        },
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    if (info.changelog.isNotBlank()) {
                        Text(
                            text = info.changelog,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    when (downloadStatus?.state) {
                        DownloadState.RUNNING -> {
                            val status = downloadStatus
                            val fraction = status?.fraction
                            if (status != null && fraction != null) {
                                LinearProgressIndicator(
                                    progress = { fraction },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                                Text(
                                    text = buildString {
                                        append("Downloading… ")
                                        append((fraction * 100).toInt())
                                        append("%")
                                        val total = status.total
                                        if (total > 0) {
                                            append(" of ")
                                            append(Formatter.formatShortFileSize(context, total))
                                        }
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            } else {
                                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                                Text(
                                    text = "Downloading…",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            TextButton(
                                onClick = {
                                    cancelDownload(context, info.versionName)
                                    downloadStatus = DownloadStatus(DownloadState.MISSING)
                                },
                            ) {
                                Text("Cancel download")
                            }
                        }

                        DownloadState.PAUSED -> {
                            Text(
                                text = "The download is paused. Connect to Wi-Fi or mobile data " +
                                    "to continue.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = { startDownload() }) {
                                    DownloadIcon()
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Resume")
                                }
                                TextButton(
                                    onClick = {
                                        cancelDownload(context, info.versionName)
                                        downloadStatus = DownloadStatus(DownloadState.MISSING)
                                    },
                                ) {
                                    Text("Cancel")
                                }
                            }
                        }

                        DownloadState.SUCCESSFUL -> {
                            Text(
                                text = "The update is downloaded and ready to install.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Button(onClick = { install() }) {
                                Icon(
                                    imageVector = Icons.Filled.InstallMobile,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Install update")
                            }
                        }

                        DownloadState.FAILED -> {
                            Text(
                                text = "The download failed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                            Button(onClick = { startDownload() }) {
                                DownloadIcon()
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Retry download")
                            }
                        }

                        DownloadState.MISSING, null -> {
                            Button(onClick = { startDownload() }) {
                                DownloadIcon()
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("Download update")
                            }
                        }
                    }

                    TextButton(onClick = { runCheck() }) {
                        RefreshIcon()
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Check again")
                    }
                }
            }

            actionMessage?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Text(
                text = "Updates are downloaded from the project's GitHub releases.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RefreshIcon() {
    Icon(
        imageVector = Icons.Filled.Refresh,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
    )
}

@Composable
private fun DownloadIcon() {
    Icon(
        imageVector = Icons.Filled.Download,
        contentDescription = null,
        modifier = Modifier.size(18.dp),
    )
}
