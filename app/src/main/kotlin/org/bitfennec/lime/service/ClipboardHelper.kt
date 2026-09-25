package org.bitfennec.lime.service

import android.content.ClipboardManager.OnPrimaryClipChangedListener
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.bitfennec.lime.R
import org.bitfennec.lime.application.Launcher
import org.bitfennec.lime.database.AppDatabase
import org.bitfennec.lime.database.dao.ClipboardDao
import org.bitfennec.lime.database.entity.Clipboard
import org.bitfennec.lime.prefs.AppPrefs
import org.bitfennec.lime.utils.clipboardManager
import org.bitfennec.lime.utils.toast
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.max

/**
 * Clipboard listener and state management.
 */
object ClipboardHelper : OnPrimaryClipChangedListener {

    const val MAX_CLIPBOARD_TEXT_LENGTH = 20000

    /**
     * Clipboard panel locked state.
     */
    var isLocked: Boolean = false

    private val eventSequence = AtomicLong(0)

    @VisibleForTesting
    internal fun resetSequenceForTesting() {
        eventSequence.set(0)
    }

    fun init() {
        Launcher.instance.context.clipboardManager.addPrimaryClipChangedListener(this)
    }

    override fun onPrimaryClipChanged() {
        val isClipboardListening = AppPrefs.getInstance().clipboard.clipboardListening.getValue()
        if (isClipboardListening) {
            val item = Launcher.instance.context.clipboardManager.primaryClip?.getItemAt(0)
            item?.takeIf { it.text?.isNotBlank() == true }?.let {
                val rawText = it.text.toString()
                handleClipText(
                    rawText = rawText,
                    daoProvider = { AppDatabase.instance.clipboardDao() },
                    scope = CoroutineScope(Dispatchers.IO),
                    suggestionEnabled = { AppPrefs.getInstance().clipboard.clipboardSuggestion.getValue() },
                    historyLimit = { AppPrefs.getInstance().clipboard.clipboardHistoryLimit.getValue().coerceIn(20, 1000) },
                    onTooLong = { Launcher.instance.context.toast(R.string.clipboard_content_too_long_toast) },
                    updateSuggestion = { time, content ->
                        AppPrefs.getInstance().internal.clipboardUpdateTime.setValue(time)
                        AppPrefs.getInstance().internal.clipboardUpdateContent.setValue(content)
                    }
                )
            }
        }
    }

    @VisibleForTesting
    internal fun handleClipText(
        rawText: String,
        daoProvider: () -> ClipboardDao,
        scope: CoroutineScope,
        suggestionEnabled: () -> Boolean,
        historyLimit: () -> Int,
        onTooLong: () -> Unit,
        updateSuggestion: (Long, String) -> Unit,
    ) {
        val currentSeq = eventSequence.incrementAndGet()
        if (rawText.length > MAX_CLIPBOARD_TEXT_LENGTH) {
            onTooLong()
            updateSuggestion(0L, "")
            return
        }

        scope.launch {
            try {
                val dao = daoProvider()
                val existing = dao.getByContent(rawText)
                val itemToSave = if (existing != null) {
                    existing.copy(time = System.currentTimeMillis())
                } else {
                    Clipboard(content = rawText)
                }
                dao.insert(itemToSave)
                val limit = historyLimit()
                val num = max(dao.getUnpinnedCount() - limit, 0)
                if (num > 0) {
                    dao.deleteOldest(num)
                }
                if (suggestionEnabled() && currentSeq == eventSequence.get()) {
                    updateSuggestion(System.currentTimeMillis(), rawText)
                }
            } catch (_: Exception) {}
        }
    }
}
