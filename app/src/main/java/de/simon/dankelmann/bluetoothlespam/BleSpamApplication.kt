package de.simon.dankelmann.bluetoothlespam

import android.app.Application
import android.util.Log
import de.simon.dankelmann.bluetoothlespam.AppContext.AppContext
import de.simon.dankelmann.bluetoothlespam.Database.AppDatabase
import de.simon.dankelmann.bluetoothlespam.Datastore.SettingsRepository
import de.simon.dankelmann.bluetoothlespam.Handlers.AdvertisementSetQueueHandler
import de.simon.dankelmann.bluetoothlespam.Helpers.BluetoothHelpers
import de.simon.dankelmann.bluetoothlespam.Helpers.DatabaseHelpers
import de.simon.dankelmann.bluetoothlespam.Helpers.ThemeManager
import kotlinx.coroutines.runBlocking
import de.simon.dankelmann.bluetoothlespam.Interfaces.Services.IAdvertisementService
import de.simon.dankelmann.bluetoothlespam.Interfaces.Services.IBluetoothLeScanService
import de.simon.dankelmann.bluetoothlespam.Services.BluetoothLeScanService


class BleSpamApplication : Application() {

    lateinit var advertisementService: IAdvertisementService
        private set

    lateinit var queueHandler: AdvertisementSetQueueHandler
        private set

    lateinit var scanService: IBluetoothLeScanService
        private set

    override fun onCreate() {
        // One-time blocking load of the DataStore-backed settings (theme mode/seed color,
        // dynamic color, blur) so every synchronous ThemeManager read below and throughout the
        // app sees real (migrated) values instead of defaults — must happen before ANY read.
        SettingsRepository.getInstance(this).warmUp()

        // Apply the user's theme preference before calling super.onCreate()
        // to ensure the theme is set before any UI is created
        ThemeManager.getInstance().applyTheme(this)

        super.onCreate()

        // Establish the app-wide context before the built-in-set sync below touches the database
        // (AppDatabase.getInstance resolves it via AppContext); MainActivity also sets this, with
        // the same applicationContext, when it later starts.
        AppContext.setContext(applicationContext)

        // Dynamic color for the classic-View screens is applied per-Activity in
        // MainActivity.onCreate() instead of app-wide here — it needs to react to the
        // theme-picker's custom seed color (DynamicColorsOptions.setContentBasedSource),
        // not just the device wallpaper, and MainActivity is this app's only Activity.

        setupAdvertisementService()
        scanService = BluetoothLeScanService(this)

        syncBuiltInSetsIfNeeded()
    }

    /**
     * On a background thread, top up the built-in advertisement sets if the generators have gained
     * entries since this install last seeded them (first-install seeding only ever runs on a
     * brand-new database). Opening the DB here runs Room's onCreate on a fresh install, so
     * [AppDatabase.freshlyCreated] distinguishes a fresh install (seeding already populates the
     * current data -- we only record the version) from an update to an existing database (we run the
     * additive [DatabaseHelpers.syncBuiltInSets]). Running only when the version is behind keeps
     * normal launches untouched.
     */
    private fun syncBuiltInSetsIfNeeded() {
        Thread {
            try {
                val database = AppDatabase.getInstance()
                // Force the database open (cheap -- one small table) so a fresh install runs Room's
                // onCreate and starts its seeding thread before we branch on freshlyCreated.
                database.advertisementSetListDao().getAll()

                if (database.freshlyCreated) {
                    // Do not record the seed version while the seed transaction is still running:
                    // if the process dies in between, the next launch must retry instead of
                    // assuming the (absent) collections are up to date.
                    var waitedMs = 0L
                    while (waitedMs < 120_000L) {
                        if (database.seedError != null) break
                        if (!database.isSeeding && waitedMs >= 250L) break
                        Thread.sleep(50L)
                        waitedMs += 50L
                    }
                }

                // Repairs the built-in list/collection graph for every launch, including installs
                // that were killed halfway through an older fire-and-forget seed.
                DatabaseHelpers.ensureBuiltInListsAndCollections()

                val settings = SettingsRepository.getInstance(this)
                val currentVersion = DatabaseHelpers.BUILT_IN_SEED_VERSION
                val collectionsMissing =
                    database.advertisementSetCollectionDao().getBuiltInCollections().isEmpty()

                if (database.seedError != null ||
                    (!database.freshlyCreated && settings.builtInSeedVersion != currentVersion) ||
                    collectionsMissing
                ) {
                    DatabaseHelpers.syncBuiltInSets()
                }

                runBlocking { settings.setBuiltInSeedVersion(currentVersion) }
                Log.d(
                    "BleSpamApplication",
                    "Built-in data ready (fresh=${database.freshlyCreated}, " +
                        "seedError=${database.seedError?.message})",
                )
            } catch (e: Exception) {
                Log.e("BleSpamApplication", "Built-in advertisement set sync failed", e)
            }
        }.start()
    }

    fun setupAdvertisementService() {
        // Switching between the legacy and modern advertiser must not leave the old queue running
        // against a service that is no longer referenced. Stop it first (this also tears down the
        // foreground service/notification), then swap in the new implementation while keeping the
        // existing queue handler so UI callbacks registered on it stay valid.
        val hadService = ::advertisementService.isInitialized
        val hadQueueHandler = ::queueHandler.isInitialized
        val previousTxPowerLevel = if (hadService) advertisementService.getTxPowerLevel() else null

        if (hadQueueHandler && queueHandler.isActive()) {
            queueHandler.deactivate(this)
        }

        advertisementService = BluetoothHelpers.getAdvertisementService(this)
        // TX power is a user preference that lives on the service instance; preserve it across
        // the swap instead of silently resetting to the default while the slider still says
        // otherwise.
        previousTxPowerLevel?.let { advertisementService.setTxPowerLevel(it) }

        if (hadQueueHandler) {
            queueHandler.setAdvertisementService(advertisementService)
        } else {
            queueHandler = AdvertisementSetQueueHandler(this, advertisementService)
        }
    }
}
