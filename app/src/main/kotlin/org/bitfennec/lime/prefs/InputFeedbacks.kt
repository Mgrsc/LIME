package org.bitfennec.lime.prefs

import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import org.bitfennec.lime.R
import org.bitfennec.lime.application.Launcher
import org.bitfennec.lime.utils.audioManager
import org.bitfennec.lime.utils.vibrator

object InputFeedbacks {

    private val vibrator: Vibrator by lazy {
        val appContext = Launcher.instance.context.applicationContext ?: Launcher.instance.context
        appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
            ?: appContext.vibrator
    }
    private val hasVibrator: Boolean by lazy {
        try {
            vibrator.hasVibrator()
        } catch (e: RuntimeException) {
            logHapticFailure("device_query", e)
            false
        }
    }
    private val effectSupport by lazy {
        try {
            val support = vibrator.areEffectsSupported(
                VibrationEffect.EFFECT_TICK,
                VibrationEffect.EFFECT_CLICK,
                VibrationEffect.EFFECT_HEAVY_CLICK,
            )
            mapOf(
                VibrationEffect.EFFECT_TICK to support.getOrElse(0) { Vibrator.VIBRATION_EFFECT_SUPPORT_NO },
                VibrationEffect.EFFECT_CLICK to support.getOrElse(1) { Vibrator.VIBRATION_EFFECT_SUPPORT_NO },
                VibrationEffect.EFFECT_HEAVY_CLICK to support.getOrElse(2) { Vibrator.VIBRATION_EFFECT_SUPPORT_NO },
            )
        } catch (e: RuntimeException) {
            logHapticFailure("effect_query", e)
            mapOf(
                VibrationEffect.EFFECT_TICK to Vibrator.VIBRATION_EFFECT_SUPPORT_NO,
                VibrationEffect.EFFECT_CLICK to Vibrator.VIBRATION_EFFECT_SUPPORT_NO,
                VibrationEffect.EFFECT_HEAVY_CLICK to Vibrator.VIBRATION_EFFECT_SUPPORT_NO,
            )
        }
    }
    private val audioManager by lazy {
        val appContext = Launcher.instance.context.applicationContext ?: Launcher.instance.context
        appContext.audioManager
    }

    private val soundPool: SoundPool by lazy {
        val audioAttributes = AudioAttributes.Builder()
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .build()
        SoundPool.Builder()
            .setMaxStreams(8)
            .setAudioAttributes(audioAttributes)
            .build()
    }

    private val soundMap = mutableMapOf<SoundEffect, Int>()

    init {
        try {
            audioManager.loadSoundEffects()
        } catch (_: Throwable) {}
        loadEmbeddedSounds()
    }

    fun warmUp() {
        try {
            soundPool
        } catch (_: Throwable) {}
    }

    private fun loadEmbeddedSounds() {
        try {
            val context = Launcher.instance.context
            soundMap[SoundEffect.Standard] = soundPool.load(context, R.raw.sound_keypress_standard, 1)
            soundMap[SoundEffect.SpaceBar] = soundPool.load(context, R.raw.sound_keypress_spacebar, 1)
            soundMap[SoundEffect.Delete] = soundPool.load(context, R.raw.sound_keypress_delete, 1)
            soundMap[SoundEffect.Return] = soundPool.load(context, R.raw.sound_keypress_return, 1)
        } catch (_: Throwable) {}
    }

    enum class HapticEvent(val systemEffect: Int) {
        TAP(HapticFeedbackConstants.KEYBOARD_TAP),
        LONG_PRESS(HapticFeedbackConstants.LONG_PRESS),
        STEP(HapticFeedbackConstants.CLOCK_TICK),
    }

    enum class HapticLevel(val value: Int, private val effect: Int?) {
        SYSTEM(0, null), OFF(1, null), LIGHT(2, VibrationEffect.EFFECT_TICK),
        MEDIUM(3, VibrationEffect.EFFECT_CLICK), STRONG(4, VibrationEffect.EFFECT_HEAVY_CLICK);

        fun effectFor(event: HapticEvent): Int? = when {
            effect == null -> null
            event == HapticEvent.STEP -> VibrationEffect.EFFECT_TICK
            else -> effect
        }

        companion object {
            fun fromValue(value: Int): HapticLevel = entries.firstOrNull { it.value == value } ?: SYSTEM
        }
    }

    private var lastHapticTimestamp: Long? = null
    private var lastStepTimestamp: Long? = null
    private val failedEffects = mutableSetOf<Int>()
    private val loggedHapticFailures = mutableSetOf<String>()

    internal fun allowHaptic(level: HapticLevel, event: HapticEvent, enabled: Boolean, now: Long): Boolean {
        if (level == HapticLevel.OFF || !enabled) return false
        if (lastHapticTimestamp?.let { now - it < 20L } == true) return false
        // yagni: fixed 50ms step interval; tune only after continuous-input device acceptance.
        if (event == HapticEvent.STEP && lastStepTimestamp?.let { now - it < 50L } == true) return false
        lastHapticTimestamp = now
        if (event == HapticEvent.STEP) lastStepTimestamp = now
        return true
    }

    @MainThread
    fun hapticFeedback(view: View, event: HapticEvent = HapticEvent.TAP) {
        // Never queue feedback after its originating touch or retain a View.
        if (Looper.myLooper() != Looper.getMainLooper()) return
        val level = HapticLevel.fromValue(AppPrefs.getInstance().internal.vibrationAmplitude.getValue())
        if (level == HapticLevel.OFF) return
        // IME windows need not take window focus; visibility and attachment are sufficient.
        val enabled = view.isAttachedToWindow && view.isShown &&
            view.isHapticFeedbackEnabled && systemHapticsEnabled(view)
        if (!enabled || !hasVibrator) return
        if (!allowHaptic(level, event, enabled, SystemClock.uptimeMillis())) return

        val effectId = level.effectFor(event)
        playHapticEffect(
            effectId,
            if (effectId == null) Vibrator.VIBRATION_EFFECT_SUPPORT_NO else effectSupport[effectId] ?: Vibrator.VIBRATION_EFFECT_SUPPORT_NO,
            playPredefined = { id ->
                val effect = VibrationEffect.createPredefined(id)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator.vibrate(effect, AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
                }
            },
            playSystem = { view.performHapticFeedback(event.systemEffect) },
        )
    }

    internal fun playHapticEffect(effectId: Int?, support: Int, playPredefined: (Int) -> Unit, playSystem: () -> Unit) {
        if (effectId != null && support != Vibrator.VIBRATION_EFFECT_SUPPORT_NO && effectId !in failedEffects) {
            try {
                playPredefined(effectId)
                return // The API acknowledges the request, not physical motor output.
            } catch (e: RuntimeException) {
                failedEffects += effectId
                logHapticFailure("effect_$effectId", e)
            }
        }
        try {
            playSystem() // A false View result must never trigger another vibration.
        } catch (e: RuntimeException) {
            logHapticFailure("system_feedback", e)
        }
    }

    private fun systemHapticsEnabled(view: View): Boolean {
        // API 33+ applies current user settings via USAGE_TOUCH; the legacy key may be stale.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return true
        return try {
            @Suppress("DEPRECATION") // Required for Android 12; no usage-based settings query exists there.
            Settings.System.getInt(view.context.contentResolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 0) != 0
        } catch (e: RuntimeException) {
            logHapticFailure("settings_query", e)
            false
        }
    }

    @MainThread
    fun hapticStatus(view: View): Int {
        if (!hasVibrator) return R.string.settings_haptic_unavailable
        if (!systemHapticsEnabled(view)) return R.string.settings_haptic_system_disabled
        val level = HapticLevel.fromValue(AppPrefs.getInstance().internal.vibrationAmplitude.getValue())
        if (level == HapticLevel.OFF) return R.string.settings_haptic_off
        val effectId = level.effectFor(HapticEvent.TAP) ?: return R.string.settings_haptic_system_description
        val ids = setOf(effectId, VibrationEffect.EFFECT_TICK)
        if (ids.any { it in failedEffects || effectSupport.getOrDefault(it, Vibrator.VIBRATION_EFFECT_SUPPORT_NO) == Vibrator.VIBRATION_EFFECT_SUPPORT_NO }) {
            return R.string.settings_haptic_fallback
        }
        return R.string.settings_haptic_manual_description
    }

    @MainThread
    fun cancelHapticFeedback() {
        try {
            vibrator.cancel()
        } catch (e: RuntimeException) {
            logHapticFailure("cancel", e)
        }
    }

    private fun logHapticFailure(reason: String, error: RuntimeException) {
        if (loggedHapticFailures.add(reason)) {
            Log.w("InputFeedbacks", """{"timestamp":"${java.time.Instant.now()}","level":"warn","service":"ime","event":"haptic.fallback","reason":"$reason","error_type":"${error.javaClass.simpleName}"}""")
        }
    }

    @VisibleForTesting
    internal fun resetForTesting() {
        lastHapticTimestamp = null
        lastStepTimestamp = null
        failedEffects.clear()
        loggedHapticFailures.clear()
    }

    enum class SoundEffect {
        Standard, SpaceBar, Delete, Return
    }

    fun getSoundLevelIndex(soundVal: Int = AppPrefs.getInstance().internal.soundOnKeyPress.getValue()): Int = when {
        soundVal in 5..10 -> 0
        soundVal < 5 -> 1
        soundVal in 11..30 -> 2
        else -> 3
    }

    fun soundEffect(effect: SoundEffect) {
        val soundOnKeyPress = AppPrefs.getInstance().internal.soundOnKeyPress.getValue()
        if (soundOnKeyPress in 5..10) return

        val volume = when {
            soundOnKeyPress < 5 -> 0.4f
            else -> ((soundOnKeyPress - 10) / 30f).coerceIn(0.1f, 1.0f)
        }

        val soundId = soundMap[effect] ?: soundMap[SoundEffect.Standard]
        if (soundId != null && soundId > 0) {
            soundPool.play(soundId, volume, volume, 1, 0, 1.0f)
        } else {
            val fx = when (effect) {
                SoundEffect.Standard -> AudioManager.FX_KEYPRESS_STANDARD
                SoundEffect.SpaceBar -> AudioManager.FX_KEYPRESS_SPACEBAR
                SoundEffect.Delete -> AudioManager.FX_KEYPRESS_DELETE
                SoundEffect.Return -> AudioManager.FX_KEYPRESS_RETURN
            }
            if (soundOnKeyPress < 5) {
                audioManager.playSoundEffect(fx, -1f)
            } else {
                audioManager.playSoundEffect(fx, volume)
            }
        }
    }
}
