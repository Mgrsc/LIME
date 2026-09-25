package org.bitfennec.lime.keyboard.spatial

import android.view.KeyEvent
import org.bitfennec.lime.keyboard.model.SoftKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdaptiveKeyCenterLearnerTest {
    @Test
    fun learnsOffsetAfterRollbackWindow() {
        var now = 0L
        val learner = learner(clock = { now }, alpha = 1f, minSamples = 1)
        val key = key(KeyEvent.KEYCODE_A)

        learner.onTouchDown("layout", key, 60f, 35f)
        learner.onTap(key)
        now = 2_001L
        learner.flushExpiredTaps()

        val offset = learner.offsetFor("layout", key)
        assertEquals(10f, offset!!.dx, 0.01f)
        assertEquals(10f, offset.dy, 0.01f)
    }

    @Test
    fun deleteWithinRollbackWindowDropsRecentSample() {
        var now = 0L
        val learner = learner(clock = { now }, alpha = 1f, minSamples = 1)
        val key = key(KeyEvent.KEYCODE_A)

        learner.onTouchDown("layout", key, 60f, 25f)
        learner.onTap(key)
        now = 1_000L
        learner.onTap(SoftKey(KeyEvent.KEYCODE_DEL))
        now = 3_001L

        assertNull(learner.offsetFor("layout", key))
    }

    @Test
    fun multipleDeletesDropMultiplePendingSamples() {
        var now = 0L
        val learner = learner(clock = { now }, alpha = 1f, minSamples = 1)
        val a = key(KeyEvent.KEYCODE_A)
        val b = key(KeyEvent.KEYCODE_B)

        learner.onTouchDown("layout", a, 60f, 25f)
        learner.onTap(a)
        now = 100L
        learner.onTouchDown("layout", b, 60f, 25f)
        learner.onTap(b)
        now = 1_000L
        learner.onTap(SoftKey(KeyEvent.KEYCODE_DEL))
        learner.onTap(SoftKey(KeyEvent.KEYCODE_DEL))
        now = 3_001L

        assertNull(learner.offsetFor("layout", a))
        assertNull(learner.offsetFor("layout", b))
    }

    @Test
    fun deleteKeepsExpiredPendingSamples() {
        var now = 0L
        val learner = learner(clock = { now }, alpha = 1f, minSamples = 1)
        val a = key(KeyEvent.KEYCODE_A)
        val b = key(KeyEvent.KEYCODE_B)

        learner.onTouchDown("layout", a, 60f, 25f)
        learner.onTap(a)
        now = 2_001L
        learner.onTouchDown("layout", b, 60f, 25f)
        learner.onTap(b)
        learner.onTap(SoftKey(KeyEvent.KEYCODE_DEL))

        assertEquals(10f, learner.offsetFor("layout", a)!!.dx, 0.01f)
        assertNull(learner.offsetFor("layout", b))
    }

    @Test
    fun clampsOffsetToKeySizeAndAbsoluteLimit() {
        var now = 0L
        val learner = learner(clock = { now }, alpha = 1f, minSamples = 1)
        val key = key(KeyEvent.KEYCODE_A)

        learner.onTouchDown("layout", key, 200f, 200f)
        learner.onTap(key)
        now = 2_001L
        learner.flushExpiredTaps()

        val offset = learner.offsetFor("layout", key)
        assertEquals(12f, offset!!.dx, 0.01f)
        assertEquals(10f, offset.dy, 0.01f)
    }

    @Test
    fun appliesEmaAcrossConfirmedSamples() {
        var now = 0L
        val learner = learner(clock = { now }, alpha = 0.5f, minSamples = 1)
        val key = key(KeyEvent.KEYCODE_A)

        learner.onTouchDown("layout", key, 60f, 20f)
        learner.onTap(key)
        now = 3_000L
        learner.onTouchDown("layout", key, 60f, 20f)
        learner.onTap(key)
        now = 6_000L
        learner.flushExpiredTaps()

        val offset = learner.offsetFor("layout", key)
        assertEquals(7.5f, offset!!.dx, 0.01f)
    }

    @Test
    fun keepsLayoutsSeparate() {
        var now = 0L
        val learner = learner(clock = { now }, alpha = 1f, minSamples = 1)
        val key = key(KeyEvent.KEYCODE_A)

        learner.onTouchDown("layout-a", key, 60f, 20f)
        learner.onTap(key)
        now = 2_001L
        learner.flushExpiredTaps()

        assertEquals(10f, learner.offsetFor("layout-a", key)!!.dx, 0.01f)
        assertNull(learner.offsetFor("layout-b", key))
    }

    @Test
    fun hidesOffsetUntilSampleThreshold() {
        var now = 0L
        val learner = learner(clock = { now }, alpha = 1f, minSamples = 2)
        val key = key(KeyEvent.KEYCODE_A)

        learner.onTouchDown("layout", key, 60f, 20f)
        learner.onTap(key)
        now = 2_001L
        learner.flushExpiredTaps()

        assertNull(learner.offsetFor("layout", key))
    }

    private fun learner(
        clock: () -> Long,
        alpha: Float,
        minSamples: Int,
    ) = AdaptiveKeyCenterLearner(
        storage = AdaptiveKeyCenterLearner.InMemoryStorage(),
        clock = clock,
        alpha = alpha,
        minSamples = minSamples,
    )

    private fun key(code: Int) = SoftKey(code).apply {
        mLeft = 0
        mRight = 100
        mTop = 0
        mBottom = 40
    }
}
