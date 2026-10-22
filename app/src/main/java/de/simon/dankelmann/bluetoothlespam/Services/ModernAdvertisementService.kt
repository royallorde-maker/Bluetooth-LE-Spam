package de.simon.dankelmann.bluetoothlespam.Services

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertisingSet
import android.bluetooth.le.AdvertisingSetCallback
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

class ModernAdvertisementService(
    private val context: Context,
): IAdvertisementService{

    // private
    private val _logTag = "AdvertisementService"
    private var _bluetoothAdapter: BluetoothAdapter? = null
    private var _advertiser: BluetoothLeAdvertiser? = null
    private var _advertisementServiceCallbacks:MutableList<IAdvertisementServiceCallback> = mutableListOf()
    private var _currentAdvertisementSet: AdvertisementSet? = null
    private var _stoppingAdvertisementSet: AdvertisementSet? = null
    private var _txPowerLevel:TxPowerLevel? = null

    init {
        refreshAdvertiser(logCapabilities = true)
    }

    /** The adapter/advertiser may be off when the app process starts. Re-resolve them lazily. */
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

    fun prepareAdvertisementSet(advertisementSet: AdvertisementSet):AdvertisementSet{
        if(_txPowerLevel != null){
            advertisementSet.advertiseSettings.txPowerLevel = _txPowerLevel!!
            advertisementSet.advertisingSetParameters.txPowerLevel = _txPowerLevel!!
        }
        advertisementSet.advertisingSetCallback = getAdvertisingSetCallback(advertisementSet)
        return advertisementSet
    }



    // Callback Implementation
    override fun startAdvertisement(advertisementSet: AdvertisementSet) {
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
            val parameters = preparedAdvertisementSet.advertisingSetParameters.build()
                ?: throw IllegalArgumentException("Invalid advertising set parameters")
            val data = preparedAdvertisementSet.advertiseData.build()
                ?: throw IllegalArgumentException("Invalid advertise data")

            if (preparedAdvertisementSet.scanResponse != null) {
                advertiser.startAdvertisingSet(
                    parameters,
                    data,
                    preparedAdvertisementSet.scanResponse!!.build(),
                    null,
                    null,
                    preparedAdvertisementSet.advertisingSetCallback,
                )
            } else {
                advertiser.startAdvertisingSet(
                    parameters,
                    data,
                    null,
                    null,
                    null,
                    preparedAdvertisementSet.advertisingSetCallback,
                )
            }
            Log.d(_logTag, "Started Modern Advertisement")
            _currentAdvertisementSet = preparedAdvertisementSet
            notifyStart(advertisementSet)
        } catch (t: Throwable) {
            Log.e(_logTag, "Could not start modern advertisement", t)
            _currentAdvertisementSet = null
            notifyFailed(advertisementSet, AdvertisementError.ADVERTISE_FAILED_INTERNAL_ERROR)
        }
    }

    override fun stopAdvertisement() {
        refreshAdvertiser()
        val advertiser = _advertiser
        val current = _currentAdvertisementSet
        if (current == null) {
            Log.d(_logTag, "No modern advertisement to stop")
            return
        }
        if (advertiser == null) {
            _currentAdvertisementSet = null
            notifyStop(current)
            return
        }
        if (!PermissionCheck.checkPermission(Manifest.permission.BLUETOOTH_ADVERTISE, context)) {
            Log.d(_logTag, "Missing permission to stop advertisement")
            _currentAdvertisementSet = null
            notifyStop(current)
            return
        }

        _stoppingAdvertisementSet = current
        _currentAdvertisementSet = null
        try {
            advertiser.stopAdvertisingSet(current.advertisingSetCallback)
        } catch (t: Throwable) {
            Log.e(_logTag, "Could not stop modern advertisement", t)
            _stoppingAdvertisementSet = null
            notifyStop(current)
        }
    }

    override fun setTxPowerLevel(txPowerLevel: TxPowerLevel) {
        _txPowerLevel = txPowerLevel
    }

    override fun getTxPowerLevel(): TxPowerLevel{
        if(_txPowerLevel != null){
            return _txPowerLevel!!
        }
        return TxPowerLevel.TX_POWER_HIGH
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
        return false
    }

    override fun hasActiveAdvertisement(): Boolean = _currentAdvertisementSet != null

    private fun getAdvertisingSetCallback(advertisementSet: AdvertisementSet): AdvertisingSetCallback {
        return object : AdvertisingSetCallback() {
            override fun onAdvertisingSetStarted(advertisingSet: AdvertisingSet?, txPower: Int, status: Int) {
                // Ignore late callbacks belonging to an advertisement we already replaced/stopped.
                if (_currentAdvertisementSet !== advertisementSet) return

                if(status == AdvertisingSetCallback.ADVERTISE_SUCCESS){
                    notifySucceeded(advertisementSet)
                }else{
                    val advertisementError = when (status) {
                        AdvertisingSetCallback.ADVERTISE_FAILED_ALREADY_STARTED -> AdvertisementError.ADVERTISE_FAILED_ALREADY_STARTED
                        AdvertisingSetCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> AdvertisementError.ADVERTISE_FAILED_FEATURE_UNSUPPORTED
                        AdvertisingSetCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> AdvertisementError.ADVERTISE_FAILED_INTERNAL_ERROR
                        AdvertisingSetCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> AdvertisementError.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS
                        AdvertisingSetCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> AdvertisementError.ADVERTISE_FAILED_DATA_TOO_LARGE
                        else -> AdvertisementError.ADVERTISE_FAILED_UNKNOWN
                    }
                    _currentAdvertisementSet = null
                    notifyFailed(advertisementSet, advertisementError)
                }
            }

            override fun onAdvertisingDataSet(advertisingSet: AdvertisingSet, status: Int) {
            }

            override fun onScanResponseDataSet(advertisingSet: AdvertisingSet, status: Int) {
            }

            override fun onAdvertisingSetStopped(advertisingSet: AdvertisingSet) {
                if (_stoppingAdvertisementSet === advertisementSet) {
                    _stoppingAdvertisementSet = null
                    notifyStop(advertisementSet)
                } else if (_currentAdvertisementSet === advertisementSet) {
                    // The system stopped it on its own (e.g. Bluetooth turned off).
                    _currentAdvertisementSet = null
                    notifyStop(advertisementSet)
                }
            }
        }
    }

}