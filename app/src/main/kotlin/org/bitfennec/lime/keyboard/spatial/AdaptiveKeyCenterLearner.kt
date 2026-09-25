package org.bitfennec.lime.keyboard.spatial

import android.content.Context
import android.content.SharedPreferences
import android.view.KeyEvent
import androidx.core.content.edit
import androidx.preference.PreferenceManager
import org.bitfennec.lime.keyboard.model.KeyCenterOffset
import org.bitfennec.lime.keyboard.model.SoftKey
import kotlin.math.min

class AdaptiveKeyCenterLearner(
    private val storage: Storage = InMemoryStorage(),
    private val clock: () -> Long = System::currentTimeMillis,
    private val alpha: Float = DEFAULT_ALPHA,
    private val rollbackWindowMs: Long = DEFAULT_ROLLBACK_WINDOW_MS,
    private val minSamples: Int = DEFAULT_MIN_SAMPLES,
    private val maxShiftPx: Float = DEFAULT_MAX_SHIFT_PX,
) {
    interface Storage {
        fun load(layoutId: String, keyCode: Int): LearnedOffset
        fun save(layoutId: String, keyCode: Int, offset: LearnedOffset)
    }

    data class LearnedOffset(
        val dx: Float = 0f,
        val dy: Float = 0f,
        val count: Int = 0,
    )

    private data class TouchSample(
        val layoutId: String,
        val keyCode: Int,
        val offsetX: Float,
        val offsetY: Float,
        val maxShiftX: Float,
        val maxShiftY: Float,
        val eventTimeMs: Long,
    )

    private val cache = mutableMapOf<String, LearnedOffset>()
    private var activeTouch: TouchSample? = null
    private val pendingTaps = mutableListOf<TouchSample>()

    fun onTouchDown(layoutId: String, key: SoftKey?, x: Float, y: Float) {
        flushExpiredTaps()
        activeTouch = if (key != null && key.isLearnableLetterKey()) {
            TouchSample(
                layoutId = layoutId,
                keyCode = key.code,
                offsetX = x - key.centerX(),
                offsetY = y - key.centerY(),
                maxShiftX = min(key.width() * MAX_SHIFT_RATIO, maxShiftPx),
                maxShiftY = min(key.height() * MAX_SHIFT_RATIO, maxShiftPx),
                eventTimeMs = clock(),
            )
        } else {
            null
        }
    }

    fun onTap(key: SoftKey) {
        val now = clock()
        if (key.code == KeyEvent.KEYCODE_DEL) {
            onDeleteKey(now)
            return
        }
        flushExpiredTaps(now)
        val sample = activeTouch
        activeTouch = null
        if (sample != null && key.code == sample.keyCode && key.isLearnableLetterKey()) {
            pendingTaps.add(sample.copy(eventTimeMs = now))
        }
    }

    fun onDeleteKey(now: Long = clock()) {
        activeTouch = null
        flushExpiredTaps(now)
        if (pendingTaps.isNotEmpty()) {
            pendingTaps.removeAt(pendingTaps.lastIndex)
        }
    }

    fun cancelActiveTouch() {
        activeTouch = null
    }

    fun offsetFor(layoutId: String, key: SoftKey): KeyCenterOffset? {
        if (!key.isLearnableLetterKey()) return null
        val learned = stateFor(layoutId, key.code)
        if (learned.count < minSamples) return null
        val dx = learned.dx.coerceIn(-maxShiftForWidth(key), maxShiftForWidth(key))
        val dy = learned.dy.coerceIn(-maxShiftForHeight(key), maxShiftForHeight(key))
        return KeyCenterOffset(dx, dy)
    }

    fun flushExpiredTaps(now: Long = clock()) {
        while (pendingTaps.isNotEmpty()) {
            val sample = pendingTaps.first()
            if (now - sample.eventTimeMs <= rollbackWindowMs) {
                return
            }
            pendingTaps.removeAt(0)
            applySample(sample)
        }
    }

    private fun applySample(sample: TouchSample) {
        val old = stateFor(sample.layoutId, sample.keyCode)
        val learned = LearnedOffset(
            dx = (alpha * sample.offsetX + (1f - alpha) * old.dx).coerceIn(-sample.maxShiftX, sample.maxShiftX),
            dy = (alpha * sample.offsetY + (1f - alpha) * old.dy).coerceIn(-sample.maxShiftY, sample.maxShiftY),
            count = old.count + 1,
        )
        cache[cacheKey(sample.layoutId, sample.keyCode)] = learned
        storage.save(sample.layoutId, sample.keyCode, learned)
    }

    private fun stateFor(layoutId: String, keyCode: Int): LearnedOffset {
        val key = cacheKey(layoutId, keyCode)
        return cache.getOrPut(key) { storage.load(layoutId, keyCode) }
    }

    private fun cacheKey(layoutId: String, keyCode: Int): String = "$layoutId:$keyCode"

    private fun SoftKey.isLearnableLetterKey(): Boolean = code in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z

    private fun SoftKey.centerX(): Float = (mLeft + mRight) / 2f

    private fun SoftKey.centerY(): Float = (mTop + mBottom) / 2f

    private fun maxShiftForWidth(key: SoftKey): Float = min(key.width() * MAX_SHIFT_RATIO, maxShiftPx)

    private fun maxShiftForHeight(key: SoftKey): Float = min(key.height() * MAX_SHIFT_RATIO, maxShiftPx)

    class InMemoryStorage : Storage {
        private val offsets = mutableMapOf<String, LearnedOffset>()

        override fun load(layoutId: String, keyCode: Int): LearnedOffset =
            offsets[cacheKey(layoutId, keyCode)] ?: LearnedOffset()

        override fun save(layoutId: String, keyCode: Int, offset: LearnedOffset) {
            offsets[cacheKey(layoutId, keyCode)] = offset
        }

        private fun cacheKey(layoutId: String, keyCode: Int): String = "$layoutId:$keyCode"
    }

    private class SharedPreferencesStorage(
        private val prefs: SharedPreferences,
    ) : Storage {
        override fun load(layoutId: String, keyCode: Int): LearnedOffset {
            val prefix = keyPrefix(layoutId, keyCode)
            return LearnedOffset(
                dx = prefs.getFloat("${prefix}dx", 0f),
                dy = prefs.getFloat("${prefix}dy", 0f),
                count = prefs.getInt("${prefix}count", 0),
            )
        }

        override fun save(layoutId: String, keyCode: Int, offset: LearnedOffset) {
            val prefix = keyPrefix(layoutId, keyCode)
            prefs.edit {
                putFloat("${prefix}dx", offset.dx)
                putFloat("${prefix}dy", offset.dy)
                putInt("${prefix}count", offset.count)
            }
        }

        private fun keyPrefix(layoutId: String, keyCode: Int): String =
            "adaptive_key_center.$layoutId.$keyCode."
    }

    companion object {
        private const val DEFAULT_ALPHA = 0.1f
        private const val DEFAULT_ROLLBACK_WINDOW_MS = 2_000L
        private const val DEFAULT_MIN_SAMPLES = 30
        private const val DEFAULT_MAX_SHIFT_PX = 12f
        private const val MAX_SHIFT_RATIO = 0.25f

        fun create(context: Context?): AdaptiveKeyCenterLearner {
            val appContext = context?.applicationContext ?: return AdaptiveKeyCenterLearner()
            val prefs = PreferenceManager.getDefaultSharedPreferences(appContext)
            return AdaptiveKeyCenterLearner(SharedPreferencesStorage(prefs))
        }
    }
}
