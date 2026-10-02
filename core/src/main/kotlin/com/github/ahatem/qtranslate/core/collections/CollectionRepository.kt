package com.github.ahatem.qtranslate.core.collections

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.github.ahatem.qtranslate.api.core.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/** A text item is independent of any translation or dictionary result. */
@Serializable
data class TextItem(val text: String, val addedAt: Long = System.currentTimeMillis())

@Serializable
enum class CollectionKind { SYSTEM, USER }

@Serializable
enum class ItemSort { NEWEST, OLDEST, ALPHABETICAL, MANUAL }

@Serializable
data class TextCollection(
    val id: String,
    val name: String,
    val kind: CollectionKind = CollectionKind.USER,
    val pinned: Boolean = false,
    val sort: ItemSort = ItemSort.NEWEST,
    val items: List<TextItem> = emptyList()
) {
    fun sortedItems(): List<TextItem> = when (sort) {
        ItemSort.NEWEST -> items.sortedBy { it.addedAt }.asReversed()
        ItemSort.OLDEST -> items.sortedBy { it.addedAt }
        ItemSort.ALPHABETICAL -> items.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.text })
        ItemSort.MANUAL -> items
    }
}

/** Single source of truth shared by popups and the main window. Mutations are serialized and persisted. */
class CollectionRepository(appDataDirectory: File, private val logger: Logger, private val json: Json) {
    companion object { const val FAVORITES_ID = "system:favorites" }
    private val key = stringPreferencesKey("collections_json")
    private val store = PreferenceDataStoreFactory.create(
        produceFile = { File(appDataDirectory, "datastore/collections.preferences_pb") }
    )
    private val mutex = Mutex()
    private val mutable = MutableStateFlow(defaultCollections())
    val collections: StateFlow<List<TextCollection>> = mutable

    private fun defaultCollections() = listOf(TextCollection(FAVORITES_ID, "Favorites", CollectionKind.SYSTEM))

    suspend fun load() = mutex.withLock {
        try {
            val saved = store.data.first()[key]
            val decoded = if (saved.isNullOrBlank()) emptyList() else json.decodeFromString<List<TextCollection>>(saved)
            mutable.value = listOf(decoded.find { it.id == FAVORITES_ID }?.copy(
                name = "Favorites", kind = CollectionKind.SYSTEM, pinned = false
            ) ?: defaultCollections().first()) + decoded.filter { it.id != FAVORITES_ID && it.kind == CollectionKind.USER }
        } catch (e: Exception) {
            logger.error("Could not load collections", e)
        }
    }

    private suspend fun change(block: (List<TextCollection>) -> List<TextCollection>) = mutex.withLock {
        val updated = block(mutable.value)
        if (updated == mutable.value) return@withLock
        // Publish only after a successful write, so every view sees the persisted state.
        store.edit { it[key] = json.encodeToString(updated) }
        mutable.value = updated
    }

    suspend fun create(name: String): String {
        val clean = name.trim().requireName()
        val id = UUID.randomUUID().toString()
        change { it + TextCollection(id, clean) }
        return id
    }

    suspend fun rename(id: String, name: String) {
        val clean = name.trim().requireName()
        change { all -> all.map { if (it.id == id && it.kind == CollectionKind.USER) it.copy(name = clean) else it } }
    }

    suspend fun delete(id: String) = change { all -> all.filterNot { it.id == id && it.kind == CollectionKind.USER } }

    suspend fun setPinned(id: String, pinned: Boolean) = change { all ->
        val updated = all.map { if (it.id == id && it.kind == CollectionKind.USER) it.copy(pinned = pinned) else it }
        updated.filter { it.kind == CollectionKind.SYSTEM } +
            updated.filter { it.kind == CollectionKind.USER && it.pinned } +
            updated.filter { it.kind == CollectionKind.USER && !it.pinned }
    }

    suspend fun reorder(id: String, direction: Int) = change { all ->
        val target = all.find { it.id == id && it.kind == CollectionKind.USER } ?: return@change all
        val users = all.filter { it.kind == CollectionKind.USER && it.pinned == target.pinned }.toMutableList()
        val from = users.indexOfFirst { it.id == id }
        val to = from + direction
        if (from < 0 || to !in users.indices) all else {
            users.add(to, users.removeAt(from))
            val other = all.filter { it.kind == CollectionKind.USER && it.pinned != target.pinned }
            all.filter { it.kind == CollectionKind.SYSTEM } + if (target.pinned) users + other else other + users
        }
    }

    suspend fun setSort(id: String, sort: ItemSort) = change { all ->
        all.map { if (it.id == id) it.copy(sort = sort) else it }
    }

    suspend fun add(id: String, text: String) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        change { all -> all.map { collection ->
            if (collection.id != id || collection.items.any { it.text == clean }) collection
            else collection.copy(items = collection.items + TextItem(clean))
        } }
    }

    suspend fun remove(id: String, text: String) = change { all ->
        all.map { if (it.id == id) it.copy(items = it.items.filterNot { item -> item.text == text }) else it }
    }

    suspend fun reorderItem(id: String, text: String, direction: Int) = change { all ->
        all.map { collection ->
            if (collection.id != id) collection else {
                val items = collection.sortedItems().toMutableList()
                val from = items.indexOfFirst { it.text == text }
                val to = from + direction
                if (from < 0 || to !in items.indices) collection
                else { items.add(to, items.removeAt(from)); collection.copy(items = items, sort = ItemSort.MANUAL) }
            }
        }
    }

    fun containing(text: String): List<TextCollection> = collections.value.filter { collection ->
        collection.items.any { it.text == text.trim() }
    }

    private fun String.requireName(): String {
        require(isNotEmpty()) { "Collection name cannot be empty" }
        return this
    }
}
