package de.simon.dankelmann.bluetoothlespam.Services

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import de.simon.dankelmann.bluetoothlespam.Helpers.BluetoothHelpers.Companion.bluetoothAdapter
import de.simon.dankelmann.bluetoothlespam.Helpers.BluetoothLeDeviceClassificationHelper
import de.simon.dankelmann.bluetoothlespam.Interfaces.Callbacks.IBluetoothLeScanCallback
import de.simon.dankelmann.bluetoothlespam.Interfaces.Services.IBluetoothLeScanService
import de.simon.dankelmann.bluetoothlespam.Models.BluetoothLeScanResult
import de.simon.dankelmann.bluetoothlespam.Models.FlipperDeviceScanResult
import de.simon.dankelmann.bluetoothlespam.Models.SpamPackageScanResult
import de.simon.dankelmann.bluetoothlespam.PermissionCheck.PermissionCheck
import java.time.Duration
import java.time.LocalDateTime

class BluetoothLeScanService(
    private val context: Context,
) : IBluetoothLeScanService, ScanCallback() {

    private val _logTag = "BluetoothLeScanService"
    private var _bluetoothAdapter:BluetoothAdapter? = null
    private var _bluetoothLeScanner:BluetoothLeScanner? = null
    private var _scanning = false
    private var _bluetoothLeScanServiceCallbacks:MutableList<IBluetoothLeScanCallback> = mutableListOf()
    private val _flipperDevicesList = mutableListOf<FlipperDeviceScanResult>()
    private val _spamPackageScanResultList = mutableListOf<SpamPackageScanResult>()
    private val _mainHandler = Handler(Looper.getMainLooper())
    private var _housekeepingRunning = false

    private val _millis_housekeeping_flipper_devices:Long = 1000
    private val _millis_housekeeping_spam_packages:Long = 1000
    private val _millis_spam_package_lifetime = 5000
    private val _millis_flipper_device_lifetime = 5000

    private val _flipperHousekeeping = object : Runnable {
        override fun run() {
            cleanFlipperDevicesList()
            if (_housekeepingRunning) {
                _mainHandler.postDelayed(this, _millis_housekeeping_flipper_devices)
            }
        }
    }

    private val _spamHousekeeping = object : Runnable {
        override fun run() {
            cleanSpamPackageList()
            if (_housekeepingRunning) {
                _mainHandler.postDelayed(this, _millis_housekeeping_spam_packages)
            }
        }
    }

    init {
        refreshScanner()
    }

    private fun refreshScanner() {
        _bluetoothAdapter = context.bluetoothAdapter()
        _bluetoothLeScanner = _bluetoothAdapter?.bluetoothLeScanner
    }

    private fun startHousekeeping() {
        if (_housekeepingRunning) return
        _housekeepingRunning = true
        _mainHandler.post(_flipperHousekeeping)
        _mainHandler.post(_spamHousekeeping)
    }

    private fun stopHousekeeping() {
        _housekeepingRunning = false
        _mainHandler.removeCallbacks(_flipperHousekeeping)
        _mainHandler.removeCallbacks(_spamHousekeeping)
    }

    private fun cleanFlipperDevicesList(){
        val currentTime = LocalDateTime.now()
        val itemsToRemove = _flipperDevicesList.filter { flipperDevice ->
            Duration.between(flipperDevice.lastSeen, currentTime).toMillis() >= _millis_flipper_device_lifetime
        }

        if(itemsToRemove.isNotEmpty()){
            _flipperDevicesList.removeAll(itemsToRemove.toSet())
            _bluetoothLeScanServiceCallbacks.toList().forEach{callback ->
                try {
                    callback.onFlipperListUpdated()
                } catch (t: Throwable) {
                    Log.e(_logTag, "Flipper list callback failed", t)
                }
            }
        }
    }

    private fun cleanSpamPackageList(){
        val currentTime = LocalDateTime.now()
        val itemsToRemove = _spamPackageScanResultList.filter { spamPackage ->
            Duration.between(spamPackage.lastSeen, currentTime).toMillis() >= _millis_spam_package_lifetime
        }

        if(itemsToRemove.isNotEmpty()){
            _spamPackageScanResultList.removeAll(itemsToRemove.toSet())
            _bluetoothLeScanServiceCallbacks.toList().forEach{callback ->
                try {
                    callback.onSpamResultPackageListUpdated()
                } catch (t: Throwable) {
                    Log.e(_logTag, "Spam list callback failed", t)
                }
            }
        }
    }



    override fun getFlipperDevicesList():MutableList<FlipperDeviceScanResult>{
        return _flipperDevicesList
    }

    override fun getSpamPackageScanResultList():MutableList<SpamPackageScanResult>{
        return _spamPackageScanResultList
    }

    override fun addBluetoothLeScanServiceCallback(callback: IBluetoothLeScanCallback){
        if(!_bluetoothLeScanServiceCallbacks.contains(callback)){
            _bluetoothLeScanServiceCallbacks.add(callback)
        }
    }
    override fun removeBluetoothLeScanServiceCallback(callback: IBluetoothLeScanCallback){
        if(_bluetoothLeScanServiceCallbacks.contains(callback)){
            _bluetoothLeScanServiceCallbacks.remove(callback)
        }
    }

    override fun isScanning():Boolean{
        return _scanning
    }

    override fun startScanning(){
        if (_scanning) return
        refreshScanner()

        if (!PermissionCheck.checkPermission(Manifest.permission.BLUETOOTH_SCAN, context)) {
            Log.d(_logTag, "Missing permission to start BLE scan")
            _scanning = false
            return
        }
        val scanner = _bluetoothLeScanner
        if (scanner == null) {
            Log.d(_logTag, "BLE scanner is null (Bluetooth off or unsupported)")
            _scanning = false
            return
        }

        val filterList:List<ScanFilter> = mutableListOf(ScanFilter.Builder().build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try {
            scanner.startScan(filterList, settings, this)
            Log.d(_logTag, "Started BLE Scan")
            _scanning = true
            startHousekeeping()
        } catch (t: Throwable) {
            Log.e(_logTag, "Could not start BLE scan", t)
            _scanning = false
            stopHousekeeping()
        }
    }

    override fun stopScanning() {
        val wasScanning = _scanning
        _scanning = false
        stopHousekeeping()

        if (wasScanning && PermissionCheck.checkPermission(Manifest.permission.BLUETOOTH_SCAN, context)) {
            try {
                _bluetoothLeScanner?.stopScan(this)
                Log.d(_logTag, "Stopped BLE Scan")
            } catch (t: Throwable) {
                Log.e(_logTag, "Could not stop BLE scan", t)
            }
        }
    }

    // android.bluetooth.le.scancallback:
    override fun onBatchScanResults(results: MutableList<ScanResult>?) {
        super.onBatchScanResults(results)
        Log.d(_logTag, "onBatchScanResults called")
    }


    override fun onScanResult(callbackType: Int, result: ScanResult?) {
        super.onScanResult(callbackType, result)
        if(result != null){
            try {
                val bluetoothLeScanResult = BluetoothLeScanResult.parseFromScanResult(context, result)

                // Check if its a Flipper
                if(BluetoothLeDeviceClassificationHelper.isFlipperDevice(bluetoothLeScanResult)){
                    val flipperDeviceScanResult = FlipperDeviceScanResult.parseFromBluetoothLeScanResult(bluetoothLeScanResult)
                    addFlipperDeviceToList(flipperDeviceScanResult)
                }

                //Check if its Spam
                if(BluetoothLeDeviceClassificationHelper.isSpamPackage(bluetoothLeScanResult)){
                    val spamPackageScanResult = SpamPackageScanResult.parseFromBluetoothLeScanResult(bluetoothLeScanResult)
                    addSpamPackageScanResultToList(spamPackageScanResult)
                }
            } catch (t: Throwable) {
                // A malformed / vendor-specific advertisement must never kill the scan pipeline.
                Log.e(_logTag, "Could not process scan result", t)
            }

            _bluetoothLeScanServiceCallbacks.toList().forEach { callback ->
                try {
                    callback.onScanResult(result)
                } catch (t: Throwable) {
                    Log.e(_logTag, "Scan result callback failed", t)
                }
            }
        }
    }

    fun addFlipperDeviceToList(flipperDeviceScanResult: FlipperDeviceScanResult){
        val elementIndex = _flipperDevicesList.indexOfFirst {
            it.address == flipperDeviceScanResult.address
        }
        val alreadyKnown = elementIndex != -1

        if(elementIndex == -1){
            _flipperDevicesList.add(flipperDeviceScanResult)
        } else {
            _flipperDevicesList[elementIndex] = flipperDeviceScanResult
        }

        _bluetoothLeScanServiceCallbacks.toList().forEach {callback ->
            try {
                callback.onFlipperListUpdated()
                callback.onFlipperDeviceDetected(flipperDeviceScanResult, alreadyKnown)
            } catch (t: Throwable) {
                Log.e(_logTag, "Flipper callback failed", t)
            }
        }
    }

    fun addSpamPackageScanResultToList(spamPackageScanResult: SpamPackageScanResult){
        val elementIndex = _spamPackageScanResultList.indexOfFirst {
            it.address == spamPackageScanResult.address
        }
        val alreadyKnown = elementIndex != -1

        if(elementIndex == -1){
            _spamPackageScanResultList.add(spamPackageScanResult)
        } else {
            _spamPackageScanResultList[elementIndex] = spamPackageScanResult
        }

        _bluetoothLeScanServiceCallbacks.toList().forEach {callback ->
            try {
                callback.onSpamResultPackageDetected(spamPackageScanResult, alreadyKnown)
                callback.onSpamResultPackageListUpdated()
            } catch (t: Throwable) {
                Log.e(_logTag, "Spam callback failed", t)
            }
        }
    }

    override fun onScanFailed(errorCode: Int) {
        super.onScanFailed(errorCode)

        _scanning = false
        stopHousekeeping()
        when(errorCode){
            SCAN_FAILED_ALREADY_STARTED -> Log.e(_logTag, "onScanFailed: SCAN_FAILED_ALREADY_STARTED")
            SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> Log.e(_logTag, "onScanFailed: SCAN_FAILED_APPLICATION_REGISTRATION_FAILED")
            SCAN_FAILED_INTERNAL_ERROR -> Log.e(_logTag, "onScanFailed: SCAN_FAILED_INTERNAL_ERROR")
            SCAN_FAILED_FEATURE_UNSUPPORTED -> Log.e(_logTag, "onScanFailed: SCAN_FAILED_FEATURE_UNSUPPORTED")
            SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES -> Log.e(_logTag, "onScanFailed: SCAN_FAILED_OUT_OF_HARDWARE_RESOURCES")
            SCAN_FAILED_SCANNING_TOO_FREQUENTLY -> Log.e(_logTag, "onScanFailed: SCAN_FAILED_SCANNING_TOO_FREQUENTLY")
            else -> Log.e(_logTag, "Unknown error for onScanFailed")
        }
    }

}