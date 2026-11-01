package org.bitfennec.lime.keyboard

import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bitfennec.lime.environment.ImeEnvironment
import org.bitfennec.lime.manager.InputModeSwitcher
import org.bitfennec.lime.utils.KeyboardLoaderUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class KeyboardGeometryTest {

    @Test
    fun heightIncreaseUpdatesKeysAndHitBounds() = withRestoredGeometry {
        verifyResize(Geometry(1080, 480), Geometry(1080, 720))
    }

    @Test
    fun heightDecreaseUpdatesKeysAndHitBounds() = withRestoredGeometry {
        verifyResize(Geometry(1080, 720), Geometry(1080, 480))
    }

    @Test
    fun widthChangeUpdatesKeysAndHitBounds() = withRestoredGeometry {
        verifyResize(Geometry(1080, 600), Geometry(900, 600))
    }

    @Test
    fun unchangedGeometryReusesCachedModel() = withRestoredGeometry {
        applyGeometry(Geometry(1080, 600))
        for (layout in LAYOUTS) {
            val first = KeyboardLoaderUtil.instance.getSoftKeyboard(layout)
            assertSame(first, KeyboardLoaderUtil.instance.getSoftKeyboard(layout))
        }
    }

    @Test
    fun resizedModelRetainsEditorAction() = withRestoredGeometry {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        InputModeSwitcher.mToggleStates.imeAction = EditorInfo.IME_ACTION_SEARCH
        for (layout in LAYOUTS) {
            applyGeometry(Geometry(1080, 480))
            val view = TextKeyboard(context).apply {
                setSoftKeyboard(KeyboardLoaderUtil.instance.getSoftKeyboard(layout))
                updateStates()
            }
            applyGeometry(Geometry(1080, 720))
            view.setSoftKeyboard(KeyboardLoaderUtil.instance.getSoftKeyboard(layout))

            val enter = checkNotNull(view.getSoftKeyboard().getKeyByCode(KeyEvent.KEYCODE_ENTER))
            assertEquals(EditorInfo.IME_ACTION_SEARCH, enter.stateId)
        }
    }

    @Test
    fun marginChangeRefreshesCachedModel() = withRestoredGeometry {
        applyGeometry(Geometry(1080, 600))
        val first = KeyboardLoaderUtil.instance.getSoftKeyboard(LAYOUTS.first())
        ImeEnvironment.keyXMargin += 3
        ImeEnvironment.keyYMargin += 4

        val refreshed = KeyboardLoaderUtil.instance.getSoftKeyboard(LAYOUTS.first())

        assertNotSame(first, refreshed)
        assertEquals(ImeEnvironment.keyXMargin, refreshed.keyXMargin)
        assertEquals(ImeEnvironment.keyYMargin, refreshed.keyYMargin)
    }

    private fun verifyResize(before: Geometry, after: Geometry) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        for (layout in LAYOUTS) {
            applyGeometry(before)
            val first = KeyboardLoaderUtil.instance.getSoftKeyboard(layout)
            val view = TextKeyboard(context).apply { setSoftKeyboard(first) }
            applyGeometry(after)
            val refreshed = KeyboardLoaderUtil.instance.getSoftKeyboard(layout)
            view.setSoftKeyboard(refreshed)
            view.measure(
                View.MeasureSpec.makeMeasureSpec(after.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(after.height, View.MeasureSpec.EXACTLY),
            )

            val keys = refreshed.mKeyRows.flatten()
            assertEquals(after.height, view.measuredHeight)
            assertEquals(after.height, keys.maxOf { it.mBottom })
            assertTrue(abs(after.width - keys.maxOf { it.mRight }) <= 1)
            assertNotEquals(first.layoutId, refreshed.layoutId)
            assertNotSame(first, refreshed)
            val enter = checkNotNull(refreshed.getKeyByCode(KeyEvent.KEYCODE_ENTER))
            assertSame(
                enter,
                refreshed.mapToKey(
                    (enter.mLeft + enter.mRight) / 2,
                    (enter.mTop + enter.mBottom) / 2,
                    useAdaptiveOffset = false,
                ),
            )
            assertTrue(keys.all { it.hitTop >= 0 && it.hitBottom <= after.height })
        }
    }

    private fun withRestoredGeometry(block: () -> Unit) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val original = Geometry(ImeEnvironment.skbWidth, ImeEnvironment.skbHeight)
            val originalXMargin = ImeEnvironment.keyXMargin
            val originalYMargin = ImeEnvironment.keyYMargin
            val originalImeAction = InputModeSwitcher.mToggleStates.imeAction
            try {
                KeyboardLoaderUtil.instance.clearKeyboardMap()
                block()
            } finally {
                applyGeometry(original)
                ImeEnvironment.keyXMargin = originalXMargin
                ImeEnvironment.keyYMargin = originalYMargin
                InputModeSwitcher.mToggleStates.imeAction = originalImeAction
                KeyboardLoaderUtil.instance.clearKeyboardMap()
            }
        }
    }

    private fun applyGeometry(geometry: Geometry) {
        for ((name, value) in listOf("skbWidth" to geometry.width, "skbHeight" to geometry.height)) {
            ImeEnvironment::class.java.getDeclaredField(name).apply {
                isAccessible = true
                setInt(null, value)
            }
        }
        ImeEnvironment.keyXMargin = 8
        ImeEnvironment.keyYMargin = 12
    }

    private data class Geometry(val width: Int, val height: Int)

    private companion object {
        val LAYOUTS = listOf(
            InputModeSwitcher.MASK_SKB_LAYOUT_QWERTY_PINYIN,
            InputModeSwitcher.MASK_SKB_LAYOUT_T9_PINYIN,
            InputModeSwitcher.MASK_SKB_LAYOUT_NUMBER,
        )
    }
}
