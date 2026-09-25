package org.bitfennec.lime.adapter

import org.bitfennec.lime.database.entity.Clipboard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipboardDiffCallbackTest {
    @Test
    fun pinAndUnpinKeepIdentityButChangeContents() {
        val original = Clipboard(content = "clipboard item", time = 1L)
        val pinned = original.copy(isKeep = 1)
        val unpinned = pinned.copy(isKeep = 0)
        val diff = ClipBoardAdapter.ClipboardDiffCallback

        assertEquals(0, original.isKeep)
        assertEquals(1, pinned.isKeep)
        assertTrue(diff.areItemsTheSame(original, pinned))
        assertFalse(diff.areContentsTheSame(original, pinned))
        assertTrue(diff.areItemsTheSame(pinned, unpinned))
        assertFalse(diff.areContentsTheSame(pinned, unpinned))
        assertTrue(diff.areContentsTheSame(original, unpinned))
    }

    @Test
    fun unpinWithFreshTimestampChangesContentAndPreservesIdentity() {
        val pinned = Clipboard(content = "clipboard item", isKeep = 1, time = 1000L)
        val unpinned = pinned.copy(isKeep = 0, time = 2000L)
        val diff = ClipBoardAdapter.ClipboardDiffCallback

        assertTrue(diff.areItemsTheSame(pinned, unpinned))
        assertFalse(diff.areContentsTheSame(pinned, unpinned))
    }

    @Test
    fun pinWithFreshTimestampChangesContentAndPreservesIdentity() {
        val original = Clipboard(content = "clipboard item", isKeep = 0, time = 1000L)
        val pinned = original.copy(isKeep = 1, time = 2000L)
        val diff = ClipBoardAdapter.ClipboardDiffCallback

        assertTrue(diff.areItemsTheSame(original, pinned))
        assertFalse(diff.areContentsTheSame(original, pinned))
    }

    @Test
    fun clipboardLayoutModeEntriesMappingMatchesOrdinalAndCodec() {
        assertEquals(0, org.bitfennec.lime.prefs.behavior.ClipboardLayoutMode.ListView.ordinal)
        assertEquals(1, org.bitfennec.lime.prefs.behavior.ClipboardLayoutMode.GridView.ordinal)
        assertEquals(2, org.bitfennec.lime.prefs.behavior.ClipboardLayoutMode.FlexboxView.ordinal)

        for (mode in org.bitfennec.lime.prefs.behavior.ClipboardLayoutMode.entries) {
            val ordinal = mode.ordinal
            assertEquals(mode, org.bitfennec.lime.prefs.behavior.ClipboardLayoutMode.entries.getOrNull(ordinal))
            assertEquals(mode, org.bitfennec.lime.prefs.behavior.ClipboardLayoutMode.decode(mode.name))
        }
    }
}
