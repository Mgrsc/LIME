package org.bitfennec.lime.prefs

import android.os.VibrationEffect
import android.os.Vibrator
import android.view.KeyEvent
import org.bitfennec.lime.keyboard.model.SoftKey
import org.bitfennec.lime.keyboard.PointerCommand
import org.bitfennec.lime.keyboard.PointerPipeline
import org.bitfennec.lime.prefs.InputFeedbacks.HapticEvent
import org.bitfennec.lime.prefs.InputFeedbacks.HapticLevel
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class InputFeedbacksTest {
    @Before
    fun setUp() {
        InputFeedbacks.resetForTesting()
    }

    @After
    fun tearDown() {
        InputFeedbacks.resetForTesting()
    }
    @Test
    fun persistedLevelsAndAllEventsHaveBoundedEffects() {
        assertEquals(HapticLevel.SYSTEM, HapticLevel.fromValue(-1))
        assertEquals(HapticLevel.SYSTEM, HapticLevel.fromValue(Int.MAX_VALUE))
        assertEquals(listOf(0, 1, 2, 3, 4), HapticLevel.entries.map { it.value })
        for (event in HapticEvent.entries) {
            assertNull(HapticLevel.SYSTEM.effectFor(event))
            assertNull(HapticLevel.OFF.effectFor(event))
            assertEquals(VibrationEffect.EFFECT_TICK, HapticLevel.LIGHT.effectFor(event))
        }
        for (event in listOf(HapticEvent.TAP, HapticEvent.LONG_PRESS)) {
            assertEquals(VibrationEffect.EFFECT_CLICK, HapticLevel.MEDIUM.effectFor(event))
            assertEquals(VibrationEffect.EFFECT_HEAVY_CLICK, HapticLevel.STRONG.effectFor(event))
        }
        assertEquals(VibrationEffect.EFFECT_TICK, HapticLevel.STRONG.effectFor(HapticEvent.STEP))
        assertEquals(VibrationEffect.EFFECT_TICK, HapticLevel.MEDIUM.effectFor(HapticEvent.STEP))
    }

    @Test
    fun disabledRequestsAndDroppedStepsDoNotExtendThrottleWindows() {
        assertFalse(InputFeedbacks.allowHaptic(HapticLevel.OFF, HapticEvent.TAP, true, 0))
        assertFalse(InputFeedbacks.allowHaptic(HapticLevel.STRONG, HapticEvent.TAP, false, 0))
        assertTrue(InputFeedbacks.allowHaptic(HapticLevel.SYSTEM, HapticEvent.TAP, true, 0))
        assertFalse(InputFeedbacks.allowHaptic(HapticLevel.SYSTEM, HapticEvent.TAP, true, 19))
        assertTrue(InputFeedbacks.allowHaptic(HapticLevel.LIGHT, HapticEvent.STEP, true, 20))
        assertFalse(InputFeedbacks.allowHaptic(HapticLevel.LIGHT, HapticEvent.STEP, true, 40))
        assertFalse(InputFeedbacks.allowHaptic(HapticLevel.LIGHT, HapticEvent.STEP, true, 69))
        assertTrue(InputFeedbacks.allowHaptic(HapticLevel.LIGHT, HapticEvent.TAP, true, 69))
        assertFalse(InputFeedbacks.allowHaptic(HapticLevel.LIGHT, HapticEvent.STEP, true, 70))
        assertTrue(InputFeedbacks.allowHaptic(HapticLevel.LIGHT, HapticEvent.STEP, true, 89))
        assertTrue(InputFeedbacks.allowHaptic(HapticLevel.LIGHT, HapticEvent.STEP, true, 139))
    }

    @Test
    fun knownAndUnknownPredefinedSupportDoNotDoublePlay() {
        for (support in listOf(Vibrator.VIBRATION_EFFECT_SUPPORT_YES, Vibrator.VIBRATION_EFFECT_SUPPORT_UNKNOWN)) {
            val effects = mutableListOf<Int>()
            InputFeedbacks.playHapticEffect(VibrationEffect.EFFECT_TICK, support,
                playPredefined = { effects += it }, playSystem = { fail("Unexpected fallback") })
            assertEquals(listOf(VibrationEffect.EFFECT_TICK), effects)
        }
    }

    @Test
    fun unsupportedAndSystemEffectsOnlyUseSystemFeedback() {
        var fallbacks = 0
        for (effect in listOf(null, VibrationEffect.EFFECT_HEAVY_CLICK)) {
            InputFeedbacks.playHapticEffect(effect, Vibrator.VIBRATION_EFFECT_SUPPORT_NO,
                playPredefined = { fail("Unsupported effect must not play") }, playSystem = { fallbacks++ })
        }
        assertEquals(2, fallbacks)
    }

    @Test
    fun runtimeFailureFallsBackAndQuarantinesOnlyTheFailedEffect() {
        var attempts = 0
        var fallbacks = 0
        repeat(2) {
            InputFeedbacks.playHapticEffect(VibrationEffect.EFFECT_CLICK, Vibrator.VIBRATION_EFFECT_SUPPORT_YES,
                playPredefined = { attempts++; throw IllegalStateException("Unavailable vibrator") },
                playSystem = { fallbacks++ })
        }
        assertEquals(1, attempts)
        assertEquals(2, fallbacks)
        InputFeedbacks.playHapticEffect(VibrationEffect.EFFECT_HEAVY_CLICK, Vibrator.VIBRATION_EFFECT_SUPPORT_YES,
            playPredefined = { attempts++ }, playSystem = { fail("Unrelated effect was disabled") })
        assertEquals(2, attempts)
        // System failure is isolated too; there is no recursive retry or one-shot fallback.
        InputFeedbacks.playHapticEffect(null, Vibrator.VIBRATION_EFFECT_SUPPORT_NO,
            playPredefined = { fail("Unexpected vibration") }, playSystem = { throw SecurityException() })
    }

    @Test
    fun longPressMenuHasOneFeedbackButVoiceAndRejectedOverlapsDoNot() {
        val key = SoftKey(KeyEvent.KEYCODE_A, "a")
        val pipeline = PointerPipeline()
        pipeline.onDown(key, 0f, 0f, context = PointerPipeline.DownContext(0, 0, 400L))
        val menu = pipeline.onLongPress(0, "a", "1", true)
        assertEquals(listOf(PointerCommand.Haptic(HapticEvent.LONG_PRESS)), menu.filterIsInstance<PointerCommand.Haptic>())

        val voice = PointerPipeline()
        voice.onDown(SoftKey(KeyEvent.KEYCODE_SPACE), 0f, 0f, context = PointerPipeline.DownContext(0, 0, 400L))
        assertTrue(voice.onLongPress(0, " ", "", true).none { it is PointerCommand.Haptic })

        val overlap = PointerPipeline()
        overlap.onDown(key, 0f, 0f, 0, PointerPipeline.DownContext(0, 0, 400L))
        overlap.onDown(SoftKey(KeyEvent.KEYCODE_B, "b"), 1f, 0f, 1, PointerPipeline.DownContext(0, 0, 400L))
        assertTrue(overlap.onLongPress(0, "a", "1", true).none { it is PointerCommand.Haptic })
    }
}
