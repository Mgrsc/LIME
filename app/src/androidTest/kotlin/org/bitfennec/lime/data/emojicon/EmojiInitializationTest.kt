package org.bitfennec.lime.data.emojicon

import android.annotation.SuppressLint
import android.graphics.Typeface
import androidx.emoji2.text.EmojiCompat
import androidx.emoji2.text.MetadataRepo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@SuppressLint("RestrictedApi")
@RunWith(AndroidJUnit4::class)
class EmojiInitializationTest {
    @Test
    fun manualConfigurationLoadsWithoutActivityAndPublishesCompletedData() = withManualCompat { compat, loader ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals(EmojiCompat.LOAD_STATE_DEFAULT, compat.loadState)
        LimeEmojiCompat.init(context)
        LimeEmojiCompat.init(context)
        assertEquals(EmojiCompat.LOAD_STATE_LOADING, compat.loadState)
        assertEquals(1, loader.loadCount)
        assertNotNull(loader.callback)
        loader.callback!!.onLoaded(MetadataRepo.create(Typeface.DEFAULT))
        val snapshot = runBlocking {
            withTimeout(5000) {
                LimeEmojiCompat.getAsFlow().first { it === compat }
                EmojiconData.snapshots.first { it?.compat === compat }
            }
        }!!
        assertTrue(snapshot.categories.isNotEmpty())
        assertSame(snapshot.categories, EmojiconData.emojiconData)
    }

    @Test
    fun metadataFailureKeepsFallbackAndDoesNotRetryOnEveryOpen() = withManualCompat { compat, loader ->
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        LimeEmojiCompat.init(context)
        loader.callback!!.onFailed(IllegalStateException("metadata unavailable"))
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(EmojiCompat.LOAD_STATE_FAILED, compat.loadState)
        assertNull(LimeEmojiCompat.getAsFlow().value)
        assertTrue(EmojiconData.emojiconData.isNotEmpty())
        LimeEmojiCompat.init(context)
        assertEquals(1, loader.loadCount)
    }

    private fun withManualCompat(block: (EmojiCompat, ControlledLoader) -> Unit) {
        val previous = EmojiCompat.get()
        val loader = ControlledLoader()
        val config = object : EmojiCompat.Config(loader) {}.setMetadataLoadStrategy(EmojiCompat.LOAD_STRATEGY_MANUAL)
        val compat = EmojiCompat.reset(config)
        try {
            block(compat, loader)
        } finally {
            EmojiCompat.reset(previous)
            LimeEmojiCompat.init(InstrumentationRegistry.getInstrumentation().targetContext)
        }
    }

    private class ControlledLoader : EmojiCompat.MetadataRepoLoader {
        var loadCount = 0
        var callback: EmojiCompat.MetadataRepoLoaderCallback? = null

        override fun load(loaderCallback: EmojiCompat.MetadataRepoLoaderCallback) {
            loadCount++
            callback = loaderCallback
        }
    }
}
