package de.simon.dankelmann.bluetoothlespam.Services

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.util.Log
import de.simon.dankelmann.bluetoothlespam.Enums.AdvertisementError
import de.simon.dankelmann.bluetoothlespam.Enums.TxPowerLevel
import de.simon.dankelmann.bluetoothlespam.Helpers.BluetoothHelpers.Companion.bluetoothAdapter
import de.simon.dankelmann.bluetoothlespam.Helpers.BluetoothHelpers.Companion.logMissingAdvertisingCapabilities
import de.simon.dankelmann.bluetoothlespam.Interfaces.Callbacks.IAdvertisementServiceCallback
import de.simon.dankelmann.bluetoothlespam.Interfaces.Services.IAdvertisementService
import de.simon.dankelmann.bluetoothlespam.Models.AdvertisementSet
import de.simon.dankelmann.bluetoothlespam.PermissionCheck.PermissionCheck

class LegacyAdvertisementService(
    private val context: Context,
): IAdvertisementService {

    // private
    private val _logTag = "AdvertisementService"
    private var _bluetoothAdapter:BluetoothAdapter? = null
    private var _advertiser: BluetoothLeAdvertiser? = null
    private var _advertisementServiceCallbacks:MutableList<IAdvertisementServiceCallback> = mutableListOf()
    private var _currentAdvertisementSet: AdvertisementSet? = null
    private var _txPowerLevel:TxPowerLevel? = null

    init {
        refreshAdvertiser(logCapabilities = true)
    }

    private fun refreshAdvertiser(logCapabilities: Boolean = false) {
        _bluetoothAdapter = context.bluetoothAdapter()
        _advertiser = _bluetoothAdapter?.bluetoothLeAdvertiser
        if (logCapabilities) {
            _bluetoothAdapter?.logMissingAdvertisingCapabilities(_logTag)
        }
    }

    private fun notifyStart(set: AdvertisementSet) {
        _advertisementServiceCallbacks.toList().forEach { it.onAdvertisementSetStart(set) }
    }

    private fun notifyStop(set: AdvertisementSet?) {
        _advertisementServiceCallbacks.toList().forEach { it.onAdvertisementSetStop(set) }
    }

    private fun notifySucceeded(set: AdvertisementSet?) {
        _advertisementServiceCallbacks.toList().forEach { it.onAdvertisementSetSucceeded(set) }
    }

    private fun notifyFailed(set: AdvertisementSet?, error: AdvertisementError) {
        _advertisementServiceCallbacks.toList().forEach { it.onAdvertisementSetFailed(set, error) }
    }

    override fun startAdvertisement(advertisementSet:AdvertisementSet){
        refreshAdvertiser()
        val advertiser = _advertiser
        if (advertiser == null) {
            Log.d(_logTag, "Advertiser is null")
            notifyFailed(advertisementSet, AdvertisementError.ADVERTISE_FAILED_FEATURE_UNSUPPORTED)
            return
        }
        if (!advertisementSet.validate()) {
            Log.d(_logTag, "Advertisement Set could not be validated")
            notifyFailed(advertisementSet, AdvertisementError.ADVERTISE_FAILED_UNKNOWN)
            return
        }
        if (!PermissionCheck.checkPermission(Manifest.permission.BLUETOOTH_ADVERTISE, context)) {
            Log.d(_logTag, "Missing permission to execute advertisement")
            notifyFailed(advertisementSet, AdvertisementError.ADVERTISE_FAILED_UNKNOWN)
            return
        }

        val preparedAdvertisementSet = prepareAdvertisementSet(advertisementSet)
        try {
            val settings = preparedAdvertisementSet.advertiseSettings.build()
                ?: throw IllegalArgumentException("Invalid advertise settings")
            val data = preparedAdvertisementSet.advertiseData.build()
                ?: throw IllegalArgumentException("Invalid advertise data")

            if (preparedAdvertisementSet.scanResponse != null) {
                advertiser.startAdvertising(
                    settings,
                    data,
                    preparedAdvertisementSet.scanResponse!!.build(),
                    preparedAdvertisementSet.advertisingCallback,
                )
            } else {
                advertiser.startAdvertising(
                    settings,
                    data,
                    preparedAdvertisementSet.advertisingCallback,
                )
            }
            Log.d(_logTag, "Started Legacy Advertisement")
            _currentAdvertisementSet = preparedAdvertisementSet
            notifyStart(advertisementSet)
        } catch (t: Throwable) {
            Log.e(_logTag, "Could not start legacy advertisement", t)
            _currentAdvertisementSet = null
            notifyFailed(advertisementSet, AdvertisementError.ADVERTISE_FAILED_INTERNAL_ERROR)
        }
    }

    override fun stopAdvertisement(){
        refreshAdvertiser()
        val advertiser = _advertiser
        val current = _currentAdvertisementSet
        if (current == null) {
            Log.d(_logTag, "No legacy advertisement to stop")
            return
        }
        if (advertiser == null ||
            !PermissionCheck.checkPermission(Manifest.permission.BLUETOOTH_ADVERTISE, context)
        ) {
            _currentAdvertisementSet = null
            notifyStop(current)
            return
        }

        _currentAdvertisementSet = null
        try {
            advertiser.stopAdvertising(current.advertisingCallback)
        } catch (t: Throwable) {
            Log.e(_logTag, "Could not stop legacy advertisement", t)
        }
        // The legacy AdvertiseCallback has no stop callback at all, so the queue advances here.
        notifyStop(current)
    }

    override fun setTxPowerLevel(txPowerLevel:TxPowerLevel){
        _txPowerLevel = txPowerLevel
        Log.d(_logTag, "Setting TX POWER")
    }

    override fun getTxPowerLevel(): TxPowerLevel{
        if(_txPowerLevel != null){
            return _txPowerLevel!!
        }
        return TxPowerLevel.TX_POWER_HIGH
    }

    fun prepareAdvertisementSet(advertisementSet: AdvertisementSet):AdvertisementSet{
        if(_txPowerLevel != null){
            advertisementSet.advertiseSettings.txPowerLevel = _txPowerLevel!!
            advertisementSet.advertisingSetParameters.txPowerLevel = _txPowerLevel!!
        }
        advertisementSet.advertisingCallback = getAdvertisingCallback(advertisementSet)
        return advertisementSet
    }

    override fun addAdvertisementServiceCallback(callback: IAdvertisementServiceCallback){
        if(!_advertisementServiceCallbacks.contains(callback)){
            _advertisementServiceCallbacks.add(callback)
        }
    }
    override fun removeAdvertisementServiceCallback(callback: IAdvertisementServiceCallback){
        if(_advertisementServiceCallbacks.contains(callback)){
            _advertisementServiceCallbacks.remove(callback)
        }
    }

    override fun isLegacyService(): Boolean {
        return true
    }

    override fun hasActiveAdvertisement(): Boolean = _currentAdvertisementSet != null

    private fun getAdvertisingCallback(advertisementSet: AdvertisementSet):AdvertiseCallback{
        return object : AdvertiseCallback() {

            override fun onStartFailure(errorCode: Int) {
                super.onStartFailure(errorCode)

                if (_currentAdvertisementSet !== advertisementSet) return

                val advertisementError = when (errorCode) {
                    AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> AdvertisementError.ADVERTISE_FAILED_ALREADY_STARTED
                    AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> AdvertisementError.ADVERTISE_FAILED_FEATURE_UNSUPPORTED
                    AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> AdvertisementError.ADVERTISE_FAILED_INTERNAL_ERROR
                    AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> AdvertisementError.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS
                    AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> AdvertisementError.ADVERTISE_FAILED_DATA_TOO_LARGE
                    else -> AdvertisementError.ADVERTISE_FAILED_UNKNOWN
                }

                _currentAdvertisementSet = null
                notifyFailed(advertisementSet, advertisementError)
            }

            override fun onStartSuccess(settingsInEffect: AdvertiseSettings?) {
                super.onStartSuccess(settingsInEffect)
                if (_currentAdvertisementSet !== advertisementSet) return
                notifySucceeded(advertisementSet)
            }
        }
    }
}