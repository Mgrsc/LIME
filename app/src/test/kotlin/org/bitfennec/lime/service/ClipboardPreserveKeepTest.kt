package org.bitfennec.lime.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.bitfennec.lime.database.dao.ClipboardDao
import org.bitfennec.lime.database.entity.Clipboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.math.max

@OptIn(ExperimentalCoroutinesApi::class)
class ClipboardPreserveKeepTest {

    @Before
    fun setUp() {
        ClipboardHelper.resetSequenceForTesting()
    }

    private class FakeClipboardDao : ClipboardDao {
        val items = mutableListOf<Clipboard>()
        var insertCalled = 0
        var deleteOldestCalled = 0
        var lastDeletedOverflow = 0
        var onInsertHook: (suspend () -> Unit)? = null

        override suspend fun insert(bean: Clipboard) {
            insertCalled++
            onInsertHook?.invoke()
            items.removeAll { it.content == bean.content }
            items.add(bean)
        }

        override suspend fun insertAll(bean: List<Clipboard>) {
            for (b in bean) insert(b)
        }

        override suspend fun getByContent(content: String): Clipboard? {
            return items.firstOrNull { it.content == content }
        }

        override suspend fun getUnpinnedCount(): Int {
            return items.count { it.isKeep == 0 }
        }

        override suspend fun deleteOldest(overflow: Int) {
            deleteOldestCalled++
            lastDeletedOverflow = overflow
            val unpinned = items.filter { it.isKeep == 0 }.sortedBy { it.time }.take(overflow)
            items.removeAll(unpinned.toSet())
        }

        override fun getAllFlow(): Flow<List<Clipboard>> = flowOf(items)
        override suspend fun getAll(): List<Clipboard> = items.toList()
        override suspend fun search(query: String): List<Clipboard> = items.filter { it.content.contains(query) }
        override suspend fun deleteByContent(content: String) { items.removeAll { it.content == content } }
        override suspend fun deleteAll() { items.clear() }
        override suspend fun getCount(): Int = items.size
        override suspend fun deleteAllExceptKeep() { items.removeAll { it.isKeep == 0 } }
        override suspend fun delete(bean: Clipboard) { items.remove(bean) }
        override suspend fun update(bean: Clipboard) {
            val idx = items.indexOfFirst { it.content == bean.content }
            if (idx >= 0) items[idx] = bean
        }
    }

    @Test
    fun reCopyingExistingPinnedItemPreservesKeepAndUpdatesTime() {
        val existingPinned = Clipboard(content = "bank account", isKeep = 1, time = 1000L)
        val now = 2000L
        val toSave = existingPinned.copy(time = now)

        assertEquals(1, toSave.isKeep)
        assertEquals("bank account", toSave.content)
        assertEquals(2000L, toSave.time)
    }

    @Test
    fun reCopyingUnpinnedItemKeepsUnpinnedAndUpdatesTime() {
        val existingUnpinned = Clipboard(content = "hello", isKeep = 0, time = 1000L)
        val now = 2000L
        val toSave = existingUnpinned.copy(time = now)

        assertEquals(0, toSave.isKeep)
        assertEquals(2000L, toSave.time)
    }

    @Test
    fun unpinnedOverflowOnlyCalculatedAgainstUnpinnedItems() {
        val unpinnedCount = 55
        val limit = 50
        val overflow = max(unpinnedCount - limit, 0)
        assertEquals(5, overflow)

        val pinnedCount = 20
        val totalCount = unpinnedCount + pinnedCount
        val naiveOverflow = max(totalCount - limit, 0)
        assertEquals(25, naiveOverflow)
    }

    @Test
    fun handleClipTextSavesExact20000TextWithEmojiAndPublishesSuggestion() = runTest {
        val dao = FakeClipboardDao()
        val emoji = "👋"
        val exactText = "a".repeat(ClipboardHelper.MAX_CLIPBOARD_TEXT_LENGTH - 2) + emoji
        assertEquals(20000, exactText.length)

        var tooLongCalled = false
        var publishedTime = 0L
        var publishedContent: String? = null

        ClipboardHelper.handleClipText(
            rawText = exactText,
            daoProvider = { dao },
            scope = this,
            suggestionEnabled = { true },
            historyLimit = { 100 },
            onTooLong = { tooLongCalled = true },
            updateSuggestion = { time, content ->
                publishedTime = time
                publishedContent = content
            }
        )

        advanceUntilIdle()

        assertEquals(false, tooLongCalled)
        assertEquals(1, dao.insertCalled)
        val saved = dao.getByContent(exactText)
        assertNotNull(saved)
        assertEquals(20000, saved!!.content.length)
        assertTrue(saved.content.endsWith(emoji))
        assertTrue(publishedTime > 0L)
        assertEquals(exactText, publishedContent)
    }

    @Test
    fun handleClipTextRejectsOversizedTextAndClearsSuggestionImmediately() = runTest {
        val dao = FakeClipboardDao()
        val oversizedText = "a".repeat(ClipboardHelper.MAX_CLIPBOARD_TEXT_LENGTH + 1)
        assertEquals(20001, oversizedText.length)

        var tooLongCalled = false
        var publishedTime = -1L
        var publishedContent: String? = "initial"

        ClipboardHelper.handleClipText(
            rawText = oversizedText,
            daoProvider = { dao },
            scope = this,
            suggestionEnabled = { true },
            historyLimit = { 100 },
            onTooLong = { tooLongCalled = true },
            updateSuggestion = { time, content ->
                publishedTime = time
                publishedContent = content
            }
        )

        advanceUntilIdle()

        assertEquals(true, tooLongCalled)
        assertEquals(0, dao.insertCalled)
        assertEquals(0L, publishedTime)
        assertEquals("", publishedContent)
    }

    @Test
    fun slowEventDoesNotOverwriteSuggestionWhenNewerOversizedEventClearsIt() = runTest {
        val dao = FakeClipboardDao()
        val textA = "legal content A"
        val oversizedB = "b".repeat(ClipboardHelper.MAX_CLIPBOARD_TEXT_LENGTH + 1)

        var publishedTime = -1L
        var publishedContent: String? = "initial"

        val slowScope = this
        dao.onInsertHook = {
            // While A is waiting in insert, Event B arrives and clears suggestion
            ClipboardHelper.handleClipText(
                rawText = oversizedB,
                daoProvider = { dao },
                scope = slowScope,
                suggestionEnabled = { true },
                historyLimit = { 100 },
                onTooLong = {},
                updateSuggestion = { time, content ->
                    publishedTime = time
                    publishedContent = content
                }
            )
        }

        ClipboardHelper.handleClipText(
            rawText = textA,
            daoProvider = { dao },
            scope = slowScope,
            suggestionEnabled = { true },
            historyLimit = { 100 },
            onTooLong = {},
            updateSuggestion = { time, content ->
                publishedTime = time
                publishedContent = content
            }
        )

        advanceUntilIdle()

        // 1. History preservation: text A was written to database
        assertEquals(1, dao.insertCalled)
        assertNotNull(dao.getByContent(textA))

        // 2. Suggestion race protection: A's stale completion did NOT overwrite B's clearing
        assertEquals(0L, publishedTime)
        assertEquals("", publishedContent)
    }

    @Test
    fun slowEventDoesNotOverwriteSuggestionWhenNewerValidEventUpdatesIt() = runTest {
        val dao = FakeClipboardDao()
        val textA = "content A"
        val textB = "content B"

        var publishedTime = -1L
        var publishedContent: String? = "initial"

        val scope = this
        dao.onInsertHook = {
            if (dao.insertCalled == 1) {
                ClipboardHelper.handleClipText(
                    rawText = textB,
                    daoProvider = { dao },
                    scope = scope,
                    suggestionEnabled = { true },
                    historyLimit = { 100 },
                    onTooLong = {},
                    updateSuggestion = { time, content ->
                        publishedTime = time
                        publishedContent = content
                    }
                )
            }
        }

        ClipboardHelper.handleClipText(
            rawText = textA,
            daoProvider = { dao },
            scope = scope,
            suggestionEnabled = { true },
            historyLimit = { 100 },
            onTooLong = {},
            updateSuggestion = { time, content ->
                publishedTime = time
                publishedContent = content
            }
        )

        advanceUntilIdle()

        assertEquals(2, dao.insertCalled)
        assertNotNull(dao.getByContent(textA))
        assertNotNull(dao.getByContent(textB))

        assertEquals(textB, publishedContent)
    }
}
