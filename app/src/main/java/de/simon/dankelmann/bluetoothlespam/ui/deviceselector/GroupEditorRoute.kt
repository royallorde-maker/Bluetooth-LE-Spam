package de.simon.dankelmann.bluetoothlespam.ui.deviceselector

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import de.simon.dankelmann.bluetoothlespam.Database.AppDatabase
import de.simon.dankelmann.bluetoothlespam.Database.Dao.CollectionWithLists
import de.simon.dankelmann.bluetoothlespam.Database.Entities.AdvertisementSetCollectionEntity
import de.simon.dankelmann.bluetoothlespam.Database.Entities.AdvertisementSetListEntity
import de.simon.dankelmann.bluetoothlespam.Database.Entities.AssociatonCollectionListEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/** Route entry point (plan §8) — builds+saves a custom [AdvertisementSetCollectionEntity]. */
@Composable
fun GroupEditorRoute(onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var collections by remember { mutableStateOf<List<CollectionWithLists>>(emptyList()) }
    var groupName by remember { mutableStateOf("") }
    var selectedListIds by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var isSaving by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val database = AppDatabase.getInstance()
            // Migration_2_3 seeds these tables on its own background thread that can still be
            // running after the migration itself returns (see StartScreen's checkDatabase) --
            // wait it out rather than loading against a still-empty DB. The timeout keeps this
            // screen usable if a seed attempt failed and left the flag set.
            withTimeoutOrNull(20_000) {
                while (database.isSeeding) delay(100)
            }
            collections = database.advertisementSetCollectionDao().getAllCollectionsWithLists()
        }
    }

    GroupEditorScreen(
        groupName = groupName,
        onGroupNameChanged = {
            groupName = it
            errorMessage = null
        },
        collections = collections,
        selectedListIds = selectedListIds,
        isSaving = isSaving,
        errorMessage = errorMessage,
        onToggleCollection = { collectionWithLists ->
            val listIds = collectionWithLists.lists.map { it.id }.toSet()
            selectedListIds = if (listIds.all { it in selectedListIds }) {
                selectedListIds - listIds
            } else {
                selectedListIds + listIds
            }
            errorMessage = null
        },
        onToggleList = { list: AdvertisementSetListEntity ->
            selectedListIds = if (list.id in selectedListIds) {
                selectedListIds - list.id
            } else {
                selectedListIds + list.id
            }
            errorMessage = null
        },
        onSave = {
            val name = groupName.trim()
            val listIds = selectedListIds
            if (name.isEmpty()) {
                errorMessage = "Enter a name for the group."
            } else if (listIds.isEmpty()) {
                errorMessage = "Select at least one list."
            } else if (!isSaving) {
                isSaving = true
                errorMessage = null
                scope.launch(Dispatchers.IO) {
                    try {
                        val database = AppDatabase.getInstance()
                        // Preserve the order shown in the editor so the saved group plays its
                        // lists in a stable, predictable order.
                        val orderedListIds = collections
                            .flatMap { collection -> collection.lists.map { it.id } }
                            .distinct()
                            .filter { it in listIds } + listIds.filter { id ->
                            collections.none { collection -> collection.lists.any { it.id == id } }
                        }

                        // A group without its list associations is unusable, so write the
                        // collection and its associations atomically: either both land or
                        // neither does.
                        database.runInTransaction {
                            val collectionId = database.advertisementSetCollectionDao().insertItem(
                                AdvertisementSetCollectionEntity(id = 0, title = name, isCustom = true),
                            ).toInt()
                            orderedListIds.forEachIndexed { index, listId ->
                                database.associationCollectionListDao().insertItem(
                                    AssociatonCollectionListEntity(
                                        id = 0,
                                        advertisementSetCollectionId = collectionId,
                                        advertisementSetListId = listId,
                                        position = index,
                                    ),
                                )
                            }
                        }
                        withContext(Dispatchers.Main) { onSaved() }
                    } catch (t: Throwable) {
                        withContext(Dispatchers.Main) {
                            isSaving = false
                            errorMessage = "Could not save the group. Please try again."
                        }
                    }
                }
            }
        },
    )
}
