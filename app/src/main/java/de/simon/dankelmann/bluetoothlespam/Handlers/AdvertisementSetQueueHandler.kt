package de.simon.dankelmann.bluetoothlespam.Handlers

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.ContinuityActionModalAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.ContinuityIos17CrashAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.ContinuityNewAirtagPopUpAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.ContinuityNewDevicePopUpAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.ContinuityNotYourDevicePopUpAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.Enums.AdvertisementError
import de.simon.dankelmann.bluetoothlespam.Enums.AdvertisementQueueMode
import de.simon.dankelmann.bluetoothlespam.Enums.AdvertisementSetType
import de.simon.dankelmann.bluetoothlespam.Helpers.QueueHandlerHelpers
import de.simon.dankelmann.bluetoothlespam.Interfaces.Callbacks.IAdvertisementServiceCallback
import de.simon.dankelmann.bluetoothlespam.Interfaces.Callbacks.IAdvertisementSetQueueHandlerCallback
import de.simon.dankelmann.bluetoothlespam.Interfaces.Services.IAdvertisementService
import de.simon.dankelmann.bluetoothlespam.Models.AdvertisementSet
import de.simon.dankelmann.bluetoothlespam.Models.AdvertisementSetCollection
import de.simon.dankelmann.bluetoothlespam.Models.AdvertisementSetList
import de.simon.dankelmann.bluetoothlespam.Services.AdvertisementForegroundService
import de.simon.dankelmann.bluetoothlespam.R
import kotlin.random.Random

/**
 * Handler that takes an advertisement set, and iterates over the set according to a given AdvertisementQueueMode.
 *
 * The job of this handler is to select the next set, and provide it to the IAdvertisementService.
 *
 * The UI code should drive the advertising via this handler (via start, stop, set advertisement set, set queue mode).
 * This handler takes care of starting and stopping services as appropriate.
 */
class AdvertisementSetQueueHandler(
    context: Context,
    adService: IAdvertisementService,
) : IAdvertisementServiceCallback {

    private var _logTag = "AdvertisementSetQueueHandler"

    private var _advertisementService: IAdvertisementService = adService
    private val _mainHandler = Handler(Looper.getMainLooper())
    private var _advanceScheduled = false
    private var _appContext: Context = context.applicationContext

    private var _advertisementQueueMode: AdvertisementQueueMode = AdvertisementQueueMode.ADVERTISEMENT_QUEUE_MODE_LINEAR
    private var _advertisementSetCollection: AdvertisementSetCollection =
        AdvertisementSetCollection()
    private var _intervalMillis: Long = QueueHandlerHelpers.getInterval(context)

    // Callbacks to listen to events of the underlying advertisement service
    private var _advertisementServiceCallbacks:MutableList<IAdvertisementServiceCallback> = mutableListOf()
    // Callbacks to listen to queue events
    private var _advertisementQueueHandlerCallbacks:MutableList<IAdvertisementSetQueueHandlerCallback> = mutableListOf()

    private var _active = false
    private var _currentAdvertisementSet: AdvertisementSet? = null
    private var _currentAdvertisementSetListIndex = 0
    private var _currentAdvertisementSetIndex = 0

    init {
        _advertisementService.addAdvertisementServiceCallback(this)
    }

    fun isActive(): Boolean {
        return _active
    }

    fun setAdvertisementQueueMode(advertisementQueueMode: AdvertisementQueueMode){
        _advertisementQueueMode = advertisementQueueMode
    }

    fun getAdvertisementQueueMode():AdvertisementQueueMode{
        return _advertisementQueueMode
    }

    fun setInterval(milliseconds: Long) {
        if (milliseconds > 0) {
            _intervalMillis = milliseconds
        }
    }

    fun setAdvertisementService(advertisementService: IAdvertisementService) {
        if (_advertisementService === advertisementService) return
        if (_active) {
            _advertisementService.stopAdvertisement()
            _active = false
        }
        _advertisementService.removeAdvertisementServiceCallback(this)

        _advertisementService = advertisementService
        _advertisementService.addAdvertisementServiceCallback(this)
    }


    fun setSelectedAdvertisementSet(advertisementSetListIndex: Int, advertisementSetIndex: Int){
        val lists = _advertisementSetCollection.advertisementSetLists
        if (advertisementSetListIndex !in lists.indices) return
        val sets = lists[advertisementSetListIndex].advertisementSets
        if (advertisementSetIndex !in sets.indices) return

        _currentAdvertisementSetListIndex = advertisementSetListIndex
        _currentAdvertisementSetIndex = advertisementSetIndex
        _currentAdvertisementSet = sets[advertisementSetIndex]
    }

    fun setAdvertisementSetCollection(advertisementSetCollection: AdvertisementSetCollection){
        if(_advertisementSetCollection != advertisementSetCollection){
            _advertisementSetCollection = advertisementSetCollection
        }

        // Reset indices
        _currentAdvertisementSet= null
        _currentAdvertisementSetListIndex = 0
        _currentAdvertisementSetIndex = 0

        // Callers must invoke this from the main thread (matches every existing call site) since
        // listeners (e.g. AdvertisementRoute) update Compose/LiveData state directly here.
        _advertisementQueueHandlerCallbacks.forEach {
            try {
                it.onAdvertisementSetCollectionChanged()
            } catch (e: Exception) {
                Log.e(_logTag, "Failed to call onAdvertisementSetCollectionChanged: ${e.message}")
            }
        }
    }

    fun getAdvertisementSetCollection(): AdvertisementSetCollection{
        return _advertisementSetCollection
    }

    // Add / Remove AdvertisementSetCollections
    fun clearAdvertisementSetCollection(){
        _advertisementSetCollection.advertisementSetLists.clear()
    }
    fun addAdvertisementSetList(advertisementSetList: AdvertisementSetList){
        if(!_advertisementSetCollection.advertisementSetLists.contains(advertisementSetList)){
            _advertisementSetCollection.advertisementSetLists.add(advertisementSetList)
        }
    }

    fun removeAdvertisementSetList(advertisementSetList: AdvertisementSetList){
        if(_advertisementSetCollection.advertisementSetLists.contains(advertisementSetList)){
            _advertisementSetCollection.advertisementSetLists.remove(advertisementSetList)
        }
    }

    // Add / Remove Callbacks
    fun addAdvertisementServiceCallback(callback: IAdvertisementServiceCallback){
        if(!_advertisementServiceCallbacks.contains(callback)){
            _advertisementServiceCallbacks.add(callback)
        }
    }
    fun removeAdvertisementServiceCallback(callback: IAdvertisementServiceCallback){
        if(_advertisementServiceCallbacks.contains(callback)){
            _advertisementServiceCallbacks.remove(callback)
        }
    }

    fun addAdvertisementQueueHandlerCallback(callback: IAdvertisementSetQueueHandlerCallback){
        if(!_advertisementQueueHandlerCallbacks.contains(callback)){
            _advertisementQueueHandlerCallbacks.add(callback)
        }
    }
    fun removeAdvertisementQueueHandlerCallback(callback: IAdvertisementSetQueueHandlerCallback){
        if(_advertisementQueueHandlerCallbacks.contains(callback)){
            _advertisementQueueHandlerCallbacks.remove(callback)
        }
    }

    fun hasCheckedItems(): Boolean {
        // Check if any advertisement set is checked
        for (list in _advertisementSetCollection.advertisementSetLists) {
            for (set in list.advertisementSets) {
                if (set.isChecked) {
                    return true
                }
            }
        }
        return false
    }

    fun toggle(context: Context) {
        if (_active) {
            deactivate(context)
        } else {
            activate(context)
        }
    }

    fun activate(context: Context) {
        if (_active) {
            return
        }

        // Cannot activate anything if nothing is selected
        if (!hasCheckedItems()) {
            Toast.makeText(context, R.string.toast_no_items_selected, Toast.LENGTH_SHORT).show()
            return
        }

        _appContext = context.applicationContext
        _active = true
        _advanceScheduled = false
        try {
            AdvertisementForegroundService.startService(context)
        } catch (t: Throwable) {
            _active = false
            Log.e(_logTag, "Could not start advertising foreground service", t)
            Toast.makeText(context, R.string.toast_advertising_start_failed, Toast.LENGTH_LONG).show()
            return
        }

        _advertisementQueueHandlerCallbacks.toList().forEach { it ->
            try {
                it.onQueueHandlerActivated()
            } catch (e: Exception) {
                Log.e(_logTag, "Failed to call onQueueHandlerActivated: ${e.message}")
            }
        }
        advertiseNextAdvertisementSet()
    }

    fun deactivate(context: Context, stopService: Boolean = true) {
        _active = false
        _advanceScheduled = false
        _mainHandler.removeCallbacksAndMessages(null)

        _advertisementService.stopAdvertisement()

        if (stopService) {
            Log.d(_logTag, "Stopping Foreground Service")
            AdvertisementForegroundService.stopService(context)
        }

        _advertisementQueueHandlerCallbacks.toList().forEach { it ->
            try {
                it.onQueueHandlerDeactivated()
            } catch (e: Exception) {
                Log.e(_logTag, "Failed to call onQueueHandlerDeactivated: ${e.message}")
            }
        }
    }

    private fun advertiseNextAdvertisementSet() {
        if (!_active) return

        selectNextAdvertisementSet()

        val nextSet = _currentAdvertisementSet
        if (nextSet == null) {
            Log.e(_logTag, "Current Advertisement Set is null.")
            deactivate(_appContext)
            return
        }

        if (!nextSet.isChecked) {
            scheduleAdvance(0L)
            return
        }

        val preparedSet = prepareAdvertisementSet(nextSet)
        _advertisementService.startAdvertisement(preparedSet)
    }

    private fun prepareAdvertisementSet(advertisementSet: AdvertisementSet): AdvertisementSet {
        return when (advertisementSet.type) {
            AdvertisementSetType.ADVERTISEMENT_TYPE_CONTINUITY_NEW_DEVICE -> ContinuityNewDevicePopUpAdvertisementSetGenerator.prepareAdvertisementSet(advertisementSet)
            AdvertisementSetType.ADVERTISEMENT_TYPE_CONTINUITY_NEW_AIRTAG -> ContinuityNewAirtagPopUpAdvertisementSetGenerator.prepareAdvertisementSet(advertisementSet)
            AdvertisementSetType.ADVERTISEMENT_TYPE_CONTINUITY_NOT_YOUR_DEVICE -> ContinuityNotYourDevicePopUpAdvertisementSetGenerator.prepareAdvertisementSet(advertisementSet)
            AdvertisementSetType.ADVERTISEMENT_TYPE_CONTINUITY_ACTION_MODALS -> ContinuityActionModalAdvertisementSetGenerator.prepareAdvertisementSet(advertisementSet)
            AdvertisementSetType.ADVERTISEMENT_TYPE_CONTINUITY_IOS_17_CRASH -> ContinuityIos17CrashAdvertisementSetGenerator.prepareAdvertisementSet(advertisementSet)
            else -> advertisementSet
        }
    }

    /**
     * Select the AdvertisementSet that should be advertised next.
     *
     * Precondition: at least one set is checked by the user.
     * The case of nothing being checked should be handled earlier.
     * If nothing is checked, this function will do nothing.
     */
    private fun selectNextAdvertisementSet() {
        // Explicit returns are used for clarity

        when (_advertisementQueueMode) {
            AdvertisementQueueMode.ADVERTISEMENT_QUEUE_MODE_LINEAR -> {
                val lists = _advertisementSetCollection.advertisementSetLists
                if (lists.isEmpty()) {
                    _currentAdvertisementSet = null
                    return
                }

                // When nothing has played yet, include index 0. The old code always started at
                // currentIndex + 1, so the first checked set was silently skipped on every start.
                val hasCurrent = _currentAdvertisementSet != null
                val startListIndex = if (hasCurrent) _currentAdvertisementSetListIndex else 0
                val startIndex = if (hasCurrent) _currentAdvertisementSetIndex + 1 else 0

                // Search the starting list, then every other list, then wrap around the starting
                // list once from its beginning so a single checked item keeps looping.
                for (offset in 0..lists.size) {
                    if (offset == lists.size && !hasCurrent) break

                    val listIndex = (startListIndex + offset) % lists.size
                    val list = lists[listIndex]
                    val fromIndex = if (offset == 0) startIndex else 0
                    if (fromIndex >= list.advertisementSets.size) continue

                    val checkedIndex = (fromIndex until list.advertisementSets.size)
                        .firstOrNull { list.advertisementSets[it].isChecked }
                    if (checkedIndex != null) {
                        _currentAdvertisementSetListIndex = listIndex
                        _currentAdvertisementSetIndex = checkedIndex
                        _currentAdvertisementSet = list.advertisementSets[checkedIndex]
                        return
                    }
                }

                // No checked set found in any list.
                _currentAdvertisementSet = null
            }

            AdvertisementQueueMode.ADVERTISEMENT_QUEUE_MODE_RANDOM -> {
                // Create a list of all checked advertisement sets across all lists
                // TODO: Cache this, don't recompute it all the time?
                val checkedSets = mutableListOf<Triple<Int, Int, AdvertisementSet>>()

                _advertisementSetCollection.advertisementSetLists.forEachIndexed { listIndex, list ->
                    list.advertisementSets.forEachIndexed { setIndex, set ->
                        if (set.isChecked) {
                            checkedSets.add(Triple(listIndex, setIndex, set))
                        }
                    }
                }

                // If we have checked items, randomly select one of them
                if (checkedSets.isNotEmpty()) {
                    val randomIndex = Random.nextInt(checkedSets.size)
                    val selected = checkedSets[randomIndex]
                    _currentAdvertisementSetListIndex = selected.first
                    _currentAdvertisementSetIndex = selected.second
                    _currentAdvertisementSet = selected.third
                } else {
                    // If no checked items, do nothing
                }
            }
        }
    }

    private fun onAdvertisementSucceeded() {
        scheduleAdvance(_intervalMillis)
    }

    private fun onAdvertisementFailed() {
        Log.d(_logTag, "Advertisement failed, trying next set")
        scheduleAdvance(_intervalMillis)
    }

    /**
     * Stops the current advertisement (if any) and advances the queue. Modern advertising calls
     * back asynchronously after the stop, while legacy has no stop callback and advances inline.
     * If there was nothing active (e.g. a start failure), advance immediately instead of waiting
     * for a callback that will never arrive.
     */
    private fun scheduleAdvance(delayMillis: Long) {
        if (!_active || _advanceScheduled) return
        _advanceScheduled = true
        _mainHandler.postDelayed({
            _advanceScheduled = false
            if (!_active) return@postDelayed

            val waitForStopCallback = !_advertisementService.isLegacyService() &&
                _advertisementService.hasActiveAdvertisement()
            _advertisementService.stopAdvertisement()
            if (!waitForStopCallback) {
                advertiseNextAdvertisementSet()
            }
        }, delayMillis.coerceAtLeast(0L))
    }

    // Callback Implementation, just pass to own Listeners
    override fun onAdvertisementSetStart(advertisementSet: AdvertisementSet?) {
        _advertisementServiceCallbacks.toList().forEach {
            try {
                it.onAdvertisementSetStart(advertisementSet)
            } catch (e:Exception){
                Log.e(_logTag, "Error in: onAdvertisementSetStart ${e.message}")
            }
        }
    }

    override fun onAdvertisementSetStop(advertisementSet: AdvertisementSet?) {
        _advertisementServiceCallbacks.toList().forEach {
            try {
                it.onAdvertisementSetStop(advertisementSet)
            } catch (e:Exception){
                Log.e(_logTag, "Error in: onAdvertisementSetStop ${e.message}")
            }
        }

        if (_active && !_advertisementService.isLegacyService()) {
            advertiseNextAdvertisementSet()
        }
    }

    override fun onAdvertisementSetSucceeded(advertisementSet: AdvertisementSet?) {
        onAdvertisementSucceeded()
        _advertisementServiceCallbacks.toList().forEach {
            try {
                it.onAdvertisementSetSucceeded(advertisementSet)
            } catch (e:Exception){
                Log.e(_logTag, "Error in: onAdvertisementSetSucceeded ${e.message}")
            }
        }
    }

    override fun onAdvertisementSetFailed(advertisementSet: AdvertisementSet?, advertisementError: AdvertisementError) {
        onAdvertisementFailed()
        _advertisementServiceCallbacks.toList().forEach {
            try {
                it.onAdvertisementSetFailed(advertisementSet, advertisementError)
            } catch (e:Exception){
                Log.e(_logTag, "Error in: onAdvertisementSetFailed ${e.message}")
            }
        }
    }
}