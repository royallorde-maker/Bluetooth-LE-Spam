package de.simon.dankelmann.bluetoothlespam.update

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Log
import androidx.core.content.FileProvider
import de.simon.dankelmann.bluetoothlespam.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale

data class UpdateInfo(
    val versionCode: Int,
    val versionName: String,
    val apkUrl: String,
    val changelog: String,
    val force: Boolean,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data object UpToDate : UpdateState
    data object Offline : UpdateState
    data class Error(val message: String) : UpdateState
}

enum class DownloadState { MISSING, RUNNING, PAUSED, SUCCESSFUL, FAILED }

data class DownloadStatus(
    val state: DownloadState,
    val downloaded: Long = 0L,
    val total: Long = -1L,
) {
    val fraction: Float?
        get() = if (total > 0) (downloaded.toFloat() / total).coerceIn(0f, 1f) else null
}

sealed interface InstallResult {
    data object Started : InstallResult
    data object NeedsInstallPermission : InstallResult
    data object NotDownloaded : InstallResult
    data object FileMissing : InstallResult
    data object InvalidPackage : InstallResult
    data object WrongPackage : InstallResult
    data object SignatureMismatch : InstallResult
    data object NoInstaller : InstallResult
    data class Failed(val message: String) : InstallResult
}

private const val PREFS_UPDATE = "app_update"
private const val KEY_DOWNLOAD_ID = "download_id"
private const val KEY_DOWNLOAD_VERSION = "download_version"
private const val MAX_MANIFEST_BYTES = 20_000

fun isOnline(context: Context): Boolean {
    return try {
        val connectivityManager =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: Exception) {
        false
    }
}

private fun isAllowedApkUrl(url: String): Boolean {
    val uri = try {
        Uri.parse(url)
    } catch (_: Exception) {
        return false
    }
    if (uri.scheme != "https") return false
    val host = (uri.host ?: "").lowercase(Locale.ROOT)
    // GitHub release assets redirect to objects.githubusercontent.com, so both host families
    // must be allowed. The leading dot rule prevents lookalikes such as evilgithub.com.
    return host == "github.com" ||
        host.endsWith(".github.com") ||
        host.endsWith(".githubusercontent.com")
}

private fun parseUpdate(json: String): UpdateInfo? {
    return try {
        val obj = JSONObject(json)
        val versionCode = obj.optInt("versionCode", -1)
        val versionName = obj.optString("versionName", "")
        val apkUrl = obj.optString("apkUrl", "")
        if (versionCode <= 0 || versionName.isBlank() || apkUrl.isBlank()) return null
        if (!isAllowedApkUrl(apkUrl)) return null
        UpdateInfo(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = apkUrl,
            changelog = obj.optString("changelog", ""),
            force = obj.optBoolean("force", false),
        )
    } catch (_: Exception) {
        null
    }
}

suspend fun checkForUpdate(context: Context): UpdateState = withContext(Dispatchers.IO) {
    if (!isOnline(context)) return@withContext UpdateState.Offline
    if (BuildConfig.UPDATE_URL.isBlank()) {
        return@withContext UpdateState.Error("No update feed is configured for this build.")
    }

    var connection: HttpURLConnection? = null
    try {
        connection = (URL(BuildConfig.UPDATE_URL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 8_000
            readTimeout = 8_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "BluetoothLESpam/${BuildConfig.VERSION_NAME}")
        }
        if (connection.responseCode !in 200..299) {
            return@withContext UpdateState.Error("Update server returned HTTP ${connection.responseCode}.")
        }
        val body = connection.inputStream.bufferedReader().use { it.readText() }
        if (body.length > MAX_MANIFEST_BYTES) {
            return@withContext UpdateState.Error("The update manifest is invalid.")
        }
        val info = parseUpdate(body)
            ?: return@withContext UpdateState.Error("The update manifest is invalid.")
        if (info.versionCode > BuildConfig.VERSION_CODE) {
            UpdateState.Available(info)
        } else {
            UpdateState.UpToDate
        }
    } catch (e: Exception) {
        if (!isOnline(context)) {
            UpdateState.Offline
        } else {
            UpdateState.Error(e.message?.take(80) ?: "Could not reach the update server.")
        }
    } finally {
        try {
            connection?.disconnect()
        } catch (_: Exception) {
            // Nothing to clean up.
        }
    }
}

private fun safeVersionName(versionName: String): String =
    versionName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(32)

private fun apkFile(context: Context, versionName: String): File {
    val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates")
    return File(directory, "BluetoothLESpam-${safeVersionName(versionName)}.apk")
}

/**
 * Starts (or restarts) the download of [info] through DownloadManager. Returns false when the
 * request could not be enqueued, so the UI can show a useful message instead of a silent no-op.
 */
fun downloadUpdate(context: Context, info: UpdateInfo): Boolean {
    return try {
        val prefs = context.getSharedPreferences(PREFS_UPDATE, Context.MODE_PRIVATE)
        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

        val previousId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (previousId >= 0L) {
            try {
                downloadManager.remove(previousId)
            } catch (_: Exception) {
                // The record may already be gone; the file cleanup below still applies.
            }
        }

        val destination = apkFile(context, info.versionName)
        destination.parentFile?.mkdirs()
        if (destination.exists() && !destination.delete()) {
            Log.w("UpdateManager", "Could not delete old update file ${destination.absolutePath}")
        }
        deleteStaleApks(context, keepVersion = info.versionName)

        val request = DownloadManager.Request(Uri.parse(info.apkUrl)).apply {
            setTitle("Bluetooth LE Spam ${info.versionName}")
            setDescription("Downloading app update")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalFilesDir(
                context,
                Environment.DIRECTORY_DOWNLOADS,
                "updates/${destination.name}",
            )
            setAllowedOverMetered(true)
            setAllowedOverRoaming(false)
            setMimeType("application/vnd.android.package-archive")
        }
        val downloadId = downloadManager.enqueue(request)
        prefs.edit()
            .putLong(KEY_DOWNLOAD_ID, downloadId)
            .putString(KEY_DOWNLOAD_VERSION, info.versionName)
            .apply()
        true
    } catch (e: Exception) {
        Log.w("UpdateManager", "Could not start update download", e)
        false
    }
}

/** Returns true when the file is a readable APK (not a truncated download or an HTML page). */
private fun isIntactApk(context: Context, file: File): Boolean {
    return try {
        file.exists() &&
            file.length() > 0L &&
            context.packageManager.getPackageArchiveInfo(file.absolutePath, 0) != null
    } catch (_: Exception) {
        false
    }
}

fun queryDownloadStatus(context: Context, versionName: String): DownloadStatus {
    return try {
        val file = apkFile(context, versionName)
        val prefs = context.getSharedPreferences(PREFS_UPDATE, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_DOWNLOAD_VERSION, "") != versionName) {
            return if (isIntactApk(context, file)) {
                DownloadStatus(DownloadState.SUCCESSFUL, file.length(), file.length())
            } else {
                DownloadStatus(DownloadState.MISSING)
            }
        }

        val downloadId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (downloadId < 0L) {
            return if (isIntactApk(context, file)) {
                DownloadStatus(DownloadState.SUCCESSFUL, file.length(), file.length())
            } else {
                DownloadStatus(DownloadState.MISSING)
            }
        }

        val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val cursor = downloadManager.query(DownloadManager.Query().setFilterById(downloadId))
        try {
            if (!cursor.moveToFirst()) {
                return if (isIntactApk(context, file)) {
                    DownloadStatus(DownloadState.SUCCESSFUL, file.length(), file.length())
                } else {
                    DownloadStatus(DownloadState.MISSING)
                }
            }
            when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                DownloadManager.STATUS_SUCCESSFUL -> {
                    if (isIntactApk(context, file)) {
                        DownloadStatus(DownloadState.SUCCESSFUL, file.length(), file.length())
                    } else {
                        DownloadStatus(DownloadState.MISSING)
                    }
                }
                DownloadManager.STATUS_FAILED -> DownloadStatus(DownloadState.FAILED)
                DownloadManager.STATUS_PAUSED -> DownloadStatus(DownloadState.PAUSED)
                else -> {
                    val downloaded = try {
                        cursor.getLong(
                            cursor.getColumnIndexOrThrow(
                                DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR,
                            ),
                        )
                    } catch (_: Exception) {
                        0L
                    }
                    val total = try {
                        cursor.getLong(
                            cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES),
                        )
                    } catch (_: Exception) {
                        -1L
                    }
                    DownloadStatus(DownloadState.RUNNING, downloaded.coerceAtLeast(0L), total)
                }
            }
        } finally {
            try {
                cursor.close()
            } catch (_: Exception) {
                // Nothing to clean up.
            }
        }
    } catch (_: Exception) {
        DownloadStatus(DownloadState.MISSING)
    }
}

fun cancelDownload(context: Context, versionName: String) {
    try {
        val prefs = context.getSharedPreferences(PREFS_UPDATE, Context.MODE_PRIVATE)
        val downloadId = prefs.getLong(KEY_DOWNLOAD_ID, -1L)
        if (downloadId >= 0L) {
            val downloadManager =
                context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            try {
                downloadManager.remove(downloadId)
            } catch (_: Exception) {
                // Already removed.
            }
        }
        prefs.edit().remove(KEY_DOWNLOAD_ID).remove(KEY_DOWNLOAD_VERSION).apply()
        apkFile(context, versionName).delete()
        deleteStaleApks(context, keepVersion = null)
    } catch (_: Exception) {
        // Best-effort cleanup only.
    }
}

private fun deleteStaleApks(context: Context, keepVersion: String?) {
    try {
        val directory = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "updates")
        val keepName = keepVersion?.let { apkFile(context, it).name }
        directory.listFiles { file ->
            file.isFile && file.name.startsWith("BluetoothLESpam-") && file.name.endsWith(".apk")
        }?.forEach { file ->
            if (keepName == null || file.name != keepName) {
                try {
                    file.delete()
                } catch (_: Exception) {
                    // Best effort.
                }
            }
        }
    } catch (_: Exception) {
        // Best-effort cleanup only.
    }
}

private fun signatureDigests(vararg signatures: Signature): Set<String> {
    return signatures.map { signature ->
        val digest = MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
        digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }.toSet()
}

@Suppress("DEPRECATION")
private fun packageSignatureDigests(
    context: Context,
    packageName: String,
    archivePath: String?,
): Set<String>? {
    val packageManager = context.packageManager
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val flags = PackageManager.GET_SIGNING_CERTIFICATES
            val info = if (archivePath == null) {
                packageManager.getPackageInfo(packageName, flags)
            } else {
                packageManager.getPackageArchiveInfo(archivePath, flags)
            }
            val signingInfo = info?.signingInfo ?: return null
            signatureDigests(*signingInfo.apkContentsSigners)
        } else {
            val flags = PackageManager.GET_SIGNATURES
            val info = if (archivePath == null) {
                packageManager.getPackageInfo(packageName, flags)
            } else {
                packageManager.getPackageArchiveInfo(archivePath, flags)
            }
            val signatures = info?.signatures ?: return null
            signatureDigests(*signatures)
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * Opens the system installer for a completed download. Every failure returns a distinct result so
 * the UI can explain what happened (in particular, signing-key mismatches after switching between
 * the upstream app and a fork release).
 */
fun installDownloadedUpdate(context: Context, versionName: String): InstallResult {
    return try {
        val file = apkFile(context, versionName)
        if (queryDownloadStatus(context, versionName).state != DownloadState.SUCCESSFUL) {
            return if (file.exists()) InstallResult.InvalidPackage else InstallResult.NotDownloaded
        }
        if (!file.exists()) return InstallResult.FileMissing

        val packageManager = context.packageManager
        @Suppress("DEPRECATION")
        val archive = packageManager.getPackageArchiveInfo(file.absolutePath, 0)
            ?: return InstallResult.InvalidPackage
        if (archive.packageName != context.packageName) {
            return InstallResult.WrongPackage
        }

        val installedSignatures =
            packageSignatureDigests(context, context.packageName, archivePath = null)
                ?: return InstallResult.InvalidPackage
        val archiveSignatures =
            packageSignatureDigests(context, archive.packageName, archivePath = file.absolutePath)
                ?: return InstallResult.InvalidPackage
        if (installedSignatures != archiveSignatures) {
            return InstallResult.SignatureMismatch
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            return InstallResult.NeedsInstallPermission
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        InstallResult.Started
    } catch (e: android.content.ActivityNotFoundException) {
        InstallResult.NoInstaller
    } catch (e: Exception) {
        Log.w("UpdateManager", "Could not open the downloaded APK", e)
        InstallResult.Failed(e.message?.take(80) ?: "Could not open the update file.")
    }
}

fun openInstallPermissionSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
    try {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    } catch (_: Exception) {
        // Some OEMs hide this screen; the system installer will still prompt when possible.
    }
}
