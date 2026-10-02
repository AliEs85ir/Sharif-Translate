package com.github.ahatem.qtranslate.core.collections

import com.github.ahatem.qtranslate.api.core.Logger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CollectionRepositoryTest {
    private val logger = object : Logger {
        override fun debug(message: String) = Unit
        override fun info(message: String) = Unit
        override fun warn(message: String) = Unit
        override fun error(message: String, error: Throwable?) = Unit
    }

    @Test fun systemCollectionAndSharedMembership() = runBlocking {
        val repo = CollectionRepository(Files.createTempDirectory("collections-test").toFile(), logger, Json)
        repo.load()
        val a = repo.create("Study")
        val b = repo.create("Math")
        repo.add(a, "a phrase")
        repo.add(a, "a phrase")
        repo.add(b, "a phrase")
        repo.add(CollectionRepository.FAVORITES_ID, "a phrase")
        repo.load() // reload from DataStore, not just the in-memory StateFlow
        assertEquals(1, repo.collections.value.first { it.id == a }.items.size)
        assertEquals(3, repo.containing("a phrase").size)
        repo.remove(a, "a phrase")
        assertEquals(2, repo.containing("a phrase").size)
        repo.rename(CollectionRepository.FAVORITES_ID, "Wrong")
        repo.delete(CollectionRepository.FAVORITES_ID)
        assertTrue(repo.collections.value.any { it.id == CollectionRepository.FAVORITES_ID && it.kind == CollectionKind.SYSTEM })
        repo.delete(b)
        assertFalse(repo.collections.value.any { it.id == b })
    }

    @Test fun orderingAndEmptyCollection() = runBlocking {
        val repo = CollectionRepository(Files.createTempDirectory("collections-order-test").toFile(), logger, Json)
        repo.load()
        val a = repo.create("A")
        val b = repo.create("B")
        assertTrue(repo.collections.value.first { it.id == a }.items.isEmpty())
        repo.reorder(b, -1)
        assertEquals(b, repo.collections.value[1].id)
        repo.setPinned(a, true)
        assertEquals(a, repo.collections.value[1].id)
        repo.setPinned(a, false)
        repo.add(b, "z")
        repo.add(b, "a")
        repo.setSort(b, ItemSort.ALPHABETICAL)
        assertEquals(listOf("a", "z"), repo.collections.value.first { it.id == b }.sortedItems().map { it.text })
        repo.reorderItem(b, "z", -1)
        assertEquals(ItemSort.MANUAL, repo.collections.value.first { it.id == b }.sort)
    }
}
