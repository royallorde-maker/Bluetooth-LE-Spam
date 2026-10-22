package de.simon.dankelmann.bluetoothlespam.ui.spamDetector

import android.Manifest
import android.bluetooth.le.ScanResult
import android.content.Context
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import de.simon.dankelmann.bluetoothlespam.BleSpamApplication
import de.simon.dankelmann.bluetoothlespam.Helpers.BluetoothHelpers.Companion.bluetoothAdapter
import de.simon.dankelmann.bluetoothlespam.Interfaces.Callbacks.IBluetoothLeScanCallback
import de.simon.dankelmann.bluetoothlespam.Models.FlipperDeviceScanResult
import de.simon.dankelmann.bluetoothlespam.Models.SpamPackageScanResult
import de.simon.dankelmann.bluetoothlespam.PermissionCheck.PermissionCheck
import de.simon.dankelmann.bluetoothlespam.R
import de.simon.dankelmann.bluetoothlespam.Services.BluetoothLeScanForegroundService

private const val LOG_TAG = "SpamDetectorRoute"

/** Route entry point (plan §6 step 4) — owns the business logic `SpamDetectorFragment` used to own. */
@Composable
fun SpamDetectorRoute() {
    val context = LocalContext.current
    val viewModel: SpamDetectorViewModel = viewModel()
    val flipperDevices = remember { mutableStateListOf<FlipperDeviceScanResult>() }
    val spamPackages = remember { mutableStateListOf<SpamPackageScanResult>() }
    val lifecycleOwner = LocalLifecycleOwner.current

    val callback = remember {
        object : IBluetoothLeScanCallback {
            override fun onScanResult(scanResult: ScanResult) {
                // Nothing to do yet
            }

            override fun onFlipperDeviceDetected(flipperDeviceScanResult: FlipperDeviceScanResult, alreadyKnown: Boolean) {
                // Nothing to do yet
            }

            override fun onFlipperListUpdated() {
                updateFlipperDevicesListView(context, flipperDevices)
            }

            override fun onSpamResultPackageDetected(spamPackageScanResult: SpamPackageScanResult, alreadyKnown: Boolean) {
                // Nothing to do yet
            }

            override fun onSpamResultPackageListUpdated() {
                updateSpamPackageListView(context, spamPackages)
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val scanService = (context.applicationContext as BleSpamApplication).scanService
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    scanService.addBluetoothLeScanServiceCallback(callback)
                    viewModel.isDetecting.value = scanService.isScanning()
                    updateFlipperDevicesListView(context, flipperDevices)
                    updateSpamPackageListView(context, spamPackages)
                }
                Lifecycle.Event.ON_PAUSE -> scanService.removeBluetoothLeScanServiceCallback(callback)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            scanService.removeBluetoothLeScanServiceCallback(callback)
        }
    }

    val isDetecting by viewModel.isDetecting.observeAsState(false)

    SpamDetectorScreen(
        isDetecting = isDetecting == true,
        flipperDevices = flipperDevices,
        spamPackages = spamPackages,
        onToggleClicked = { onToggleButtonClicked(context, viewModel) },
    )
}

private fun updateFlipperDevicesListView(context: Context, flipperDevices: SnapshotStateList<FlipperDeviceScanResult>) {
    val scanService = (context.applicationContext as BleSpamApplication).scanService
    val serviceItems = scanService.getFlipperDevicesList().toList()
    val serviceAddresses = serviceItems.mapTo(hashSetOf()) { it.address }

    serviceItems.forEach { newFlipperDevice ->
        val oldIndex = flipperDevices.indexOfFirst { it.address == newFlipperDevice.address }
        if (oldIndex != -1) {
            flipperDevices[oldIndex] = newFlipperDevice
        } else {
            flipperDevices.add(newFlipperDevice)
        }
    }
    // The service expires devices after 5s; mirror that removal in the UI instead of keeping
    // stale devices on screen until the next process start.
    flipperDevices.removeAll { it.address !in serviceAddresses }
}

private fun updateSpamPackageListView(context: Context, spamPackages: SnapshotStateList<SpamPackageScanResult>) {
    val scanService = (context.applicationContext as BleSpamApplication).scanService
    val serviceItems = scanService.getSpamPackageScanResultList().toList()
    val serviceAddresses = serviceItems.mapTo(hashSetOf()) { it.address }

    serviceItems.forEach { newSpamPackage ->
        val oldIndex = spamPackages.indexOfFirst { it.address == newSpamPackage.address }
        if (oldIndex != -1) {
            spamPackages[oldIndex] = newSpamPackage
        } else {
            spamPackages.add(newSpamPackage)
        }
    }
    spamPackages.removeAll { it.address !in serviceAddresses }
}

private fun onToggleButtonClicked(context: Context, viewModel: SpamDetectorViewModel) {
    val scanService = (context.applicationContext as BleSpamApplication).scanService
    if (scanService.isScanning()) {
        BluetoothLeScanForegroundService.stopService(context)
        Log.d(LOG_TAG, "Stopped spam detection")
        viewModel.isDetecting.value = false
        return
    }

    if (!PermissionCheck.checkPermission(Manifest.permission.BLUETOOTH_SCAN, context)) {
        Toast.makeText(context, R.string.toast_missing_scan_permission, Toast.LENGTH_LONG).show()
        return
    }
    val adapter = context.bluetoothAdapter()
    if (adapter == null || !adapter.isEnabled) {
        Toast.makeText(context, R.string.toast_bluetooth_disabled, Toast.LENGTH_LONG).show()
        return
    }

    try {
        BluetoothLeScanForegroundService.startService(context)
        viewModel.isDetecting.value = true
        Log.d(LOG_TAG, "Started spam detection")
    } catch (t: Throwable) {
        Log.e(LOG_TAG, "Could not start spam detection", t)
        viewModel.isDetecting.value = false
        Toast.makeText(context, R.string.toast_advertising_start_failed, Toast.LENGTH_LONG).show()
    }
}
