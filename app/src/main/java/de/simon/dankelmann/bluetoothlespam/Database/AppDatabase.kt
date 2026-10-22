package de.simon.dankelmann.bluetoothlespam.Database

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.ContinuityActionModalAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.ContinuityIos17CrashAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.ContinuityNewAirtagPopUpAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.ContinuityNewDevicePopUpAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.ContinuityNotYourDevicePopUpAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.EasySetupBudsAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.EasySetupWatchAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.FastPairDevicesAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.IAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.FastPairDebugAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.FastPairNonProductionAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.FastPairPhoneSetupAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.LovespousePlayAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.LovespouseStopAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.AdvertisementSetGenerators.SwiftPairAdvertisementSetGenerator
import de.simon.dankelmann.bluetoothlespam.AppContext.AppContext
import de.simon.dankelmann.bluetoothlespam.Database.Dao.AdvertiseDataDao
import de.simon.dankelmann.bluetoothlespam.Database.Dao.AdvertiseDataManufacturerSpecificDataDao
import de.simon.dankelmann.bluetoothlespam.Database.Dao.AdvertiseDataServiceDataDao
import de.simon.dankelmann.bluetoothlespam.Database.Dao.AdvertiseSettingsDao
import de.simon.dankelmann.bluetoothlespam.Database.Dao.AdvertisementSetCollectionDao
import de.simon.dankelmann.bluetoothlespam.Database.Dao.AdvertisementSetDao
import de.simon.dankelmann.bluetoothlespam.Database.Dao.AdvertisementSetListDao
import de.simon.dankelmann.bluetoothlespam.Database.Dao.AdvertisingSetParametersDao
import de.simon.dankelmann.bluetoothlespam.Database.Dao.AssociationCollectionListDao
import de.simon.dankelmann.bluetoothlespam.Database.Dao.AssociationListSetDao
import de.simon.dankelmann.bluetoothlespam.Database.Dao.PeriodicAdvertisingParametersDao
import de.simon.dankelmann.bluetoothlespam.Database.Entities.AdvertiseDataEntity
import de.simon.dankelmann.bluetoothlespam.Database.Entities.AdvertiseDataManufacturerSpecificDataEntity
import de.simon.dankelmann.bluetoothlespam.Database.Entities.AdvertiseDataServiceDataEntity
import de.simon.dankelmann.bluetoothlespam.Database.Entities.AdvertiseSettingsEntity
import de.simon.dankelmann.bluetoothlespam.Database.Entities.AdvertisementSetCollectionEntity
import de.simon.dankelmann.bluetoothlespam.Database.Entities.AdvertisementSetEntity
import de.simon.dankelmann.bluetoothlespam.Database.Entities.AdvertisementSetListEntity
import de.simon.dankelmann.bluetoothlespam.Database.Entities.AdvertisingSetParametersEntity
import de.simon.dankelmann.bluetoothlespam.Database.Entities.AssociatonCollectionListEntity
import de.simon.dankelmann.bluetoothlespam.Database.Entities.AssociationListSetEntity
import de.simon.dankelmann.bluetoothlespam.Database.Entities.PeriodicAdvertisingParametersEntity
import de.simon.dankelmann.bluetoothlespam.Database.Migrations.Migration_1_2
import de.simon.dankelmann.bluetoothlespam.Database.Migrations.Migration_2_3
import de.simon.dankelmann.bluetoothlespam.Database.Migrations.Migration_3_4
import de.simon.dankelmann.bluetoothlespam.Helpers.DatabaseHelpers

@androidx.room.Database(
    entities = [AdvertiseDataEntity::class,
                AdvertiseDataManufacturerSpecificDataEntity::class,
                AdvertiseDataServiceDataEntity::class,
                AdvertisementSetCollectionEntity::class,
                AdvertisementSetEntity::class,
                AdvertisementSetListEntity::class,
                AdvertiseSettingsEntity::class,
                AdvertisingSetParametersEntity::class,
                AssociatonCollectionListEntity::class,
                AssociationListSetEntity::class,
                PeriodicAdvertisingParametersEntity::class],
    version = 4,
    exportSchema = true)
abstract class AppDatabase : RoomDatabase() {

    // Written by seeding threads, polled from UI coroutines.
    @Volatile var isSeeding = false

    // Non-null when a seed attempt failed; the next launch's repair pass retries the missing rows.
    @Volatile var seedError: Throwable? = null

    // Set true in the Room onCreate callback when the database is first created, so the upgrade
    // sync (DatabaseHelpers.syncBuiltInSets via BleSpamApplication) can tell a fresh install
    // (seeding handles it) from an update to an existing database (sync handles it).
    @Volatile var freshlyCreated = false
    abstract fun advertiseDataDao(): AdvertiseDataDao
    abstract fun advertiseDataManufacturerSpecificDataDao(): AdvertiseDataManufacturerSpecificDataDao
    abstract fun advertiseDataServiceDataDao(): AdvertiseDataServiceDataDao

    abstract fun advertisementSetCollectionDao(): AdvertisementSetCollectionDao

    abstract fun advertisementSetDao(): AdvertisementSetDao

    abstract fun advertisementSetListDao(): AdvertisementSetListDao

    abstract fun advertiseSettingsDao(): AdvertiseSettingsDao

    abstract fun advertisingSetParametersDao(): AdvertisingSetParametersDao

    abstract fun associationCollectionListDao(): AssociationCollectionListDao

    abstract fun associationListSetDao(): AssociationListSetDao

    abstract fun periodicAdvertisingParametersDao(): PeriodicAdvertisingParametersDao


    companion object {
        private const val _logTag = "AppDatabase"
        private var INSTANCE: AppDatabase? = null

        fun getInstance(): AppDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(AppContext.getContext()).also { INSTANCE = it }
            }

        private fun buildDatabase(context: Context) =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, "BluetoothLeSpamDatabase.db")
                .addCallback(seedDatabaseCallback(context))
                .addMigrations(Migration_1_2, Migration_2_3, Migration_3_4)
                //.fallbackToDestructiveMigration()
                .build()

        private fun seedDatabaseCallback(context: Context): Callback {
            return object : Callback() {
                override fun onCreate(db: SupportSQLiteDatabase) {
                    super.onCreate(db)
                    val database = getInstance()
                    database.freshlyCreated = true
                    database.seedError = null
                    // Mark seeding synchronously, before the worker starts. Otherwise callers that
                    // check isSeeding immediately after the first DB access can see false and
                    // conclude an empty database is ready.
                    database.isSeeding = true
                    Thread {
                        synchronized(this) {
                            seedingThread.run()
                        }
                    }.start()
                }
            }
        }

        // Single source of truth for which generators supply the built-in advertisement sets --
        // used both by first-install seeding (below) and by the upgrade sync
        // (DatabaseHelpers.syncBuiltInSets), so an existing install can be topped up with whatever
        // the current generators produce.
        val builtInAdvertisementSetGenerators: List<IAdvertisementSetGenerator>
            get() = listOf(
                FastPairDevicesAdvertisementSetGenerator(),
                FastPairPhoneSetupAdvertisementSetGenerator(),
                FastPairNonProductionAdvertisementSetGenerator(),
                FastPairDebugAdvertisementSetGenerator(),

                //ContinuityDevicePopUpAdvertisementSetGenerator(),
                ContinuityNotYourDevicePopUpAdvertisementSetGenerator(),
                ContinuityNewDevicePopUpAdvertisementSetGenerator(),
                ContinuityNewAirtagPopUpAdvertisementSetGenerator(),
                ContinuityActionModalAdvertisementSetGenerator(),
                ContinuityIos17CrashAdvertisementSetGenerator(),

                SwiftPairAdvertisementSetGenerator(),

                EasySetupWatchAdvertisementSetGenerator(),
                EasySetupBudsAdvertisementSetGenerator(),

                LovespousePlayAdvertisementSetGenerator(),
                LovespouseStopAdvertisementSetGenerator()
            )

        val seedingThread = Runnable {
            Log.d(_logTag, "Starting Database Seeding")
            // First launch must stay responsive while thousands of rows are generated and
            // inserted; running the seed at background priority keeps the UI/render threads
            // schedulable on slower devices instead of tripping the input-dispatch ANR watchdog.
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            val database = getInstance()
            database.isSeeding = true
            database.seedError = null

            try {
                // Collect all generated sets first, then bulk-insert them. The previous
                // one-DAO-call-per-row loop ran tens of thousands of statements on the main
                // SQLite connection and could take minutes on a fresh install.
                val advertisementSets = builtInAdvertisementSetGenerators.flatMap { generator ->
                    generator.getAdvertisementSets(null)
                }

                database.runInTransaction {
                    DatabaseHelpers.saveAdvertisementSets(advertisementSets)
                    DatabaseHelpers.seedBuiltInListsAndCollections()
                }
                Log.d(_logTag, "Database Seeding finished")
            } catch (t: Throwable) {
                database.seedError = t
                Log.e(_logTag, "Database Seeding failed", t)
            } finally {
                database.isSeeding = false
            }
        }
    }
}