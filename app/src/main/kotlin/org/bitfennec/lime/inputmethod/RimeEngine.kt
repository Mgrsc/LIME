package org.bitfennec.lime.inputmethod

import android.view.KeyEvent
import org.bitfennec.lime.application.CustomConstant
import org.bitfennec.lime.utils.KeyEventUtils
import org.bitfennec.lime.core.CandidateListItem
import org.bitfennec.lime.core.Rime
import org.bitfennec.lime.core.RimeSnapshot
import org.bitfennec.lime.utils.QwertyPinYinUtils
import org.bitfennec.lime.manager.InputModeSwitcher
import java.util.Locale

object RimeEngine {
    // A null index identifies the literal input, which must never be sent to Rime's selector.
    private data class CandidateReference(val pageNo: Int, val index: Int?)

    private var pinyins: Array<String> = emptyArray() // Candidate pinyin list
    var t9LockedCount: Int = 0
        private set
    var showCandidates: List<CandidateListItem> = emptyList() // Active candidates to display
    var showComposition: String = "" // Composition string displayed above candidates
    var compositionCursorPos: Int = 0 // Composition cursor position
    private var candidateReferences: List<CandidateReference> = emptyList()
    private var expandedCandidates: List<CandidateListItem> = emptyList()
    private var expandedCandidateReferences: List<CandidateReference> = emptyList()
    private var currentCandidatePageNo = 0
    private var currentCandidatePageHasNext = false
    private var rawCompositionPreedit = ""
    private var compositionLiteralText = ""
    private var rawCompositionLength = 0
    private var rawCompositionCursorPos = 0
    private var rawInput = ""
    private var currentSchema = ""
    private var backspaceKeyCode = 0
    private var kpLeftKeyCode = 0
    private var kpRightKeyCode = 0
    private const val MASK_CASE_LOWER = 0
    private var charCase = 0x0000
    private var englishStartedWithCapsLock = false

    fun ensureStarted(): Boolean {
        return try {
            Rime.getInstance(false)
            Rime.isStarted
        } catch (_: Throwable) {
            false
        }
    }

    fun setUserDictWritesEnabled(enabled: Boolean) {
        Rime.setUserDictWritesEnabled(enabled)
    }

    fun selectSchema(mod: String): Boolean {
        charCase = MASK_CASE_LOWER
        englishStartedWithCapsLock = false
        Rime.getInstance(false)
        return Rime.selectRimeSchema(mod).also { selected ->
            if (selected) {
                currentSchema = mod
                Rime.snapshotState()?.let(::updateCandidatesOrCommitText)
            }
        }
    }

    fun getCurrentRimeSchema(): String {
        return currentSchema.ifEmpty { runCatching { Rime.getCurrentRimeSchema() }.getOrDefault("") }
    }

    /** Whether composition is completed */
    fun isFinish(): Boolean {
        return rawInput.isEmpty()
    }

    fun onNormalKey(event: KeyEvent): String? {
        val keyCode = event.keyCode
        val resolvedChar = KeyEventUtils.getResolvedKeyChar(event)
        val keyChar = if (keyCode == KeyEvent.KEYCODE_APOSTROPHE) {
            if (isFinish()) '/'.code else '\''.code
        } else resolvedChar

        val schema = getCurrentRimeSchema()
        if (schema == CustomConstant.SCHEMA_EN && isFinish()) {
            englishStartedWithCapsLock = event.metaState and KeyEvent.META_CAPS_LOCK_ON != 0
        }
        val isT9 = InputModeSwitcher.isChineseT9 || schema == CustomConstant.SCHEMA_ZH_T9
        val rimeKeyChar = when {
            isT9 && keyCode in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> t9KeyCode(keyCode)
            isT9 && keyCode in KeyEvent.KEYCODE_2..KeyEvent.KEYCODE_9 -> {
                '2'.code + (keyCode - KeyEvent.KEYCODE_2)
            }
            schema != CustomConstant.SCHEMA_EN && keyChar > 0 ->
                keyChar.toChar().lowercaseChar().code
            else -> keyChar
        }
        val snapshot = Rime.processKeySnapshot(rimeKeyChar, 0)
        return snapshot?.let(::updateCandidatesOrCommitText)
    }

    fun onDeleteKey(): String? {
        return processDelAction()?.let(::updateCandidatesOrCommitText)
    }

    fun selectCandidate(index: Int, expectedPageNo: Int? = null): String? {
        if (expectedPageNo != null && expectedPageNo != currentCandidatePageNo) return null
        val reference = expandedCandidateReferences.getOrNull(index)
            ?: candidateReferences.getOrNull(index)
            ?: return null
        val candidateIndex = reference.index
        if (candidateIndex == null) {
            if (!isEnglishSchema() || isFinish()) return null
            val literal = rawInput
            reset()
            return literal
        }
        val snapshot = Rime.selectCandidateOnPageSnapshot(
            expectedPageNo ?: currentCandidatePageNo,
            reference.pageNo,
            candidateIndex,
        ) ?: return null
        return updateCandidatesOrCommitText(snapshot)
    }

    fun deleteCandidate(index: Int): String? {
        val reference = expandedCandidateReferences.getOrNull(index)
            ?: candidateReferences.getOrNull(index)
            ?: return null
        val candidateIndex = reference.index ?: return null
        val snapshot = try {
            Rime.deleteCandidateOnPageSnapshot(
                currentCandidatePageNo,
                reference.pageNo,
                candidateIndex,
            )
        } catch (_: UnsatisfiedLinkError) {
            null
        } ?: return null
        return updateCandidatesOrCommitText(snapshot)
    }

    fun getCandidateDeletableType(index: Int): Int {
        val reference = expandedCandidateReferences.getOrNull(index)
            ?: candidateReferences.getOrNull(index)
            ?: return Rime.DELETABLE_NONE
        val candidateIndex = reference.index ?: return Rime.DELETABLE_NONE
        return try {
            Rime.getCandidateDeletableType(reference.pageNo, candidateIndex)
        } catch (_: UnsatisfiedLinkError) {
            Rime.DELETABLE_NONE
        }
    }

    fun getNextExpandedCandidates(): List<CandidateListItem> {
        if (!currentCandidatePageHasNext) return emptyList()
        val snapshot = Rime.moveCandidatePageSnapshot(currentCandidatePageNo, 1) ?: return emptyList()
        updateCandidatesOrCommitText(snapshot, resetExpandedCandidates = false)
        expandedCandidates = expandedCandidates + showCandidates
        expandedCandidateReferences = expandedCandidateReferences + candidateReferences
        if (isEnglishSchema()) {
            // Must stay synchronized with expandedCandidates so republished state preserves the literal at slot 0
            // and does not regress to the latest page's candidates.
            showCandidates = expandedCandidates
            candidateReferences = expandedCandidateReferences
        }
        return expandedCandidates
    }

    fun selectPinyin(index: Int) {
        val selectedPinyin = pinyins.getOrNull(index)?.lowercase(Locale.ROOT) ?: return
        Rime.selectT9PrefixSnapshot(selectedPinyin)
            ?.let(::updateCandidatesOrCommitText)
    }

    fun setAssociationCandidates(candidates: List<CandidateListItem>) {
        pinyins = emptyArray()
        t9LockedCount = 0
        showCandidates = candidates
        candidateReferences = emptyList()
        expandedCandidates = candidates
        expandedCandidateReferences = emptyList()
        showComposition = ""
    }

    fun selectAssociation(index: Int): String {
        val chosenText = showCandidates.getOrNull(index)?.text.orEmpty()
        showCandidates = emptyList()
        expandedCandidates = emptyList()
        expandedCandidateReferences = emptyList()
        showComposition = ""
        pinyins = emptyArray()
        return chosenText
    }

    fun rawCompositionText(): String {
        val schema = getCurrentRimeSchema()
        return if (schema == CustomConstant.SCHEMA_EN) {
            rawInput
        } else if (schema == CustomConstant.SCHEMA_ZH_T9 || InputModeSwitcher.isChineseT9) {
            showComposition.replace("'", "").lowercase(Locale.ROOT).ifEmpty { rawInput }
        } else {
            compositionLiteralText.ifEmpty { rawInput }.filterNot { it == '\'' || it.isWhitespace() }
        }
    }

    fun editorCompositionText(): String =
        editorCompositionText(
            schema = getCurrentRimeSchema(),
            rawInput = rawInput,
            showComposition = showComposition,
            rawCompositionPreedit = rawCompositionPreedit,
            literalText = compositionLiteralText,
        )

    internal fun editorCompositionText(
        schema: String,
        rawInput: String,
        showComposition: String,
        rawCompositionPreedit: String,
        literalText: String = rawInput,
    ): String = if (schema == CustomConstant.SCHEMA_EN) {
        rawCompositionPreedit
    } else if (schema == CustomConstant.SCHEMA_ZH_T9 || InputModeSwitcher.isChineseT9) {
        val candidate = showComposition.replace("'", "").lowercase(Locale.ROOT)
        if (candidate.isNotEmpty() && candidate.all { it in 'a'..'z' }) {
            candidate
        } else {
            ""
        }
    } else {
        literalText.ifEmpty { rawInput }.filterNot { it == '\'' || it.isWhitespace() }
    }

    fun reset() {
        showCandidates = emptyList()
        pinyins = emptyArray()
        t9LockedCount = 0
        showComposition = ""
        compositionCursorPos = 0
        candidateReferences = emptyList()
        expandedCandidates = emptyList()
        expandedCandidateReferences = emptyList()
        currentCandidatePageNo = 0
        currentCandidatePageHasNext = false
        rawCompositionPreedit = ""
        compositionLiteralText = ""
        rawCompositionLength = 0
        rawCompositionCursorPos = 0
        rawInput = ""
        englishStartedWithCapsLock = false
        runCatching { Rime.clearCompositionSnapshot()?.let(::updateCandidatesOrCommitText) }
        if(charCase == KeyEvent.META_SHIFT_ON) charCase = MASK_CASE_LOWER
    }

    fun destroy() = Rime.destroy()

    fun moveCompositionCursor(targetIndex: Int) {
        val clampedTarget = targetIndex.coerceIn(0, rawCompositionLength)
        val delta = clampedTarget - rawCompositionCursorPos
        if (delta < 0) {
            moveCompositionCursorByKey("KP_Left", -delta)
        } else if (delta > 0) {
            moveCompositionCursorByKey("KP_Right", delta)
        }
    }

    fun stepCompositionCursor(direction: Int) {
        if (direction < 0 && rawCompositionCursorPos > 0) {
            moveCompositionCursorByKey("KP_Left", 1)
        } else if (direction > 0 && rawCompositionCursorPos < rawCompositionLength) {
            moveCompositionCursorByKey("KP_Right", 1)
        }
    }

    // Librime navigation:
    // In librime (gear/navigator.cc), standard XK_Left/XK_Right bind to Rewind/Forward (jumping whole syllables).
    // Keypad keys XK_KP_Left (0xff96) and XK_KP_Right (0xff98) bind to LeftByChar and RightByChar (single-character navigation).
    // We use KP_* key names with X11 keypad constants as fallback to ensure single-character precision never fails.
    private fun moveCompositionCursorByKey(keyName: String, count: Int) {
        val keyCode = when (keyName) {
            "KP_Left" -> {
                if (kpLeftKeyCode == 0) {
                    val resolved = getRimeKeycodeByName("KP_Left")
                    kpLeftKeyCode = if (resolved > 0) resolved else 0xff96
                }
                kpLeftKeyCode
            }
            "KP_Right" -> {
                if (kpRightKeyCode == 0) {
                    val resolved = getRimeKeycodeByName("KP_Right")
                    kpRightKeyCode = if (resolved > 0) resolved else 0xff98
                }
                kpRightKeyCode
            }
            else -> getRimeKeycodeByName(keyName)
        }
        if (keyCode <= 0 || count <= 0) return
        var snapshot: RimeSnapshot? = null
        repeat(count) {
            snapshot = Rime.processKeySnapshot(keyCode, 0) ?: return
        }
        snapshot?.let(::updateCandidatesOrCommitText)
    }

    fun processDelAction(): RimeSnapshot? {
        if (backspaceKeyCode == 0) {
            backspaceKeyCode = getRimeKeycodeByName("BackSpace")
        }
        return Rime.processKeySnapshot(backspaceKeyCode, 0)
    }

    private fun updateCandidatesOrCommitText(
        snapshot: RimeSnapshot,
        resetExpandedCandidates: Boolean = true,
    ): String? {
        val committedText = snapshot.commitText?.let(::consumeCommit)
        val rawCandidates = snapshot.page.candidates.map { CandidateListItem(it.comment, it.text) }
        val compositionText = snapshot.composition.preedit.orEmpty()
        val compositionPage = snapshot.page.pageNo
        val rimeSchema = snapshot.schemaId
        val isT9 = rimeSchema == CustomConstant.SCHEMA_ZH_T9 || InputModeSwitcher.isChineseT9
        fun formatCandidateText(text: String): String {
            if (isT9) return text
            if (rimeSchema == CustomConstant.SCHEMA_EN) {
                return formatEnglishCandidate(text, snapshot.rawInput)
            }
            return when (charCase) {
                KeyEvent.META_SHIFT_ON -> text.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                KeyEvent.META_CAPS_LOCK_ON -> text.uppercase()
                else -> text.lowercase()
            }
        }

        val literal = snapshot.rawInput.takeIf { rimeSchema == CustomConstant.SCHEMA_EN }.orEmpty()
        val candidateEntries = buildList {
            if (literal.isNotEmpty() && resetExpandedCandidates) {
                add(CandidateListItem("", literal) to CandidateReference(compositionPage, null))
            }
            rawCandidates.forEachIndexed { index, content ->
                val text = formatCandidateText(content.text)
                if (literal.isEmpty() || text != literal) {
                    add(CandidateListItem(content.comment, text) to CandidateReference(
                        compositionPage,
                        snapshot.page.candidates[index].pageIndex,
                    ))
                }
            }
        }
        showCandidates = candidateEntries.map { it.first }
        candidateReferences = candidateEntries.map { it.second }
        if (resetExpandedCandidates) {
            expandedCandidates = showCandidates
            expandedCandidateReferences = candidateReferences
        }
        var composition = getCurrentComposition(
            rawCandidates,
            compositionText,
            rimeSchema,
            snapshot.t9Metadata?.preedit.orEmpty(),
        )
        composition = if (isT9) {
            composition.lowercase(Locale.ROOT)
        } else {
            when (charCase) {
                KeyEvent.META_SHIFT_ON -> composition.lowercase().replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
                KeyEvent.META_CAPS_LOCK_ON -> composition.uppercase()
                else -> composition.lowercase()
            }
        }
        pinyins = snapshot.t9Metadata?.prefixOptions ?: emptyArray()
        t9LockedCount = snapshot.t9Metadata?.lockedCount ?: 0
        currentCandidatePageNo = snapshot.page.pageNo
        currentCandidatePageHasNext = snapshot.page.hasNext
        rawCompositionPreedit = compositionText
        compositionLiteralText = snapshot.composition.literalText
        rawCompositionLength = snapshot.composition.length
        rawCompositionCursorPos = snapshot.composition.cursorPos
        rawInput = snapshot.rawInput
        currentSchema = snapshot.schemaId
        compositionCursorPos = snapshot.composition.cursorPos
        showComposition = composition
        return committedText
    }

    /** Obtains candidate pinyin combination */
    fun getPrefixs(): Array<String> {
        return pinyins
    }

    private fun getCurrentComposition(
        candidates: List<CandidateListItem>,
        composition: String,
        rimeSchema: String,
        t9Preedit: String,
    ): String {
        if (rimeSchema == CustomConstant.SCHEMA_EN) return ""
        if (composition.isEmpty()) return ""
        if (candidates.isEmpty()) return if (rimeSchema == CustomConstant.SCHEMA_ZH_T9) t9Preedit.ifEmpty { composition.lowercase(Locale.ROOT) } else composition
        val firstCand = candidates.first()
        val comment = firstCand.comment
        val result = when (rimeSchema) {
            CustomConstant.SCHEMA_ZH_T9 -> {
                t9Preedit.ifEmpty { composition }
            }
            CustomConstant.SCHEMA_ZH_DOUBLE_FLYPY -> {
                composition
            }
            else -> {
                if (comment.isNotBlank() && comment.startsWith("~")) composition
                else QwertyPinYinUtils.getQwertyComposition(composition, comment)
            }
        }
        return if (!composition.endsWith("'") && result.endsWith("'")) result.dropLast(1) else result
    }

    fun isEnglishSchema(): Boolean = getCurrentRimeSchema() == CustomConstant.SCHEMA_EN

    private fun consumeCommit(commitText: String): String {
        if (isEnglishSchema()) {
            // Note: Rime's snapshot.rawInput is already cleared upon commit, so the retained rawInput field
            // reflects the pre-commit typed text for correct casing propagation.
            val formatted = formatEnglishCandidate(commitText, rawInput)
            rawInput = ""
            return formatted
        }
        rawInput = ""
        return when (charCase) {
            KeyEvent.META_SHIFT_ON -> commitText.lowercase()
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
            KeyEvent.META_CAPS_LOCK_ON -> commitText.uppercase()
            else -> commitText.lowercase()
        }
    }

    private fun formatEnglishCandidate(text: String, input: String): String {
        val letters = input.filter { it in 'a'..'z' || it in 'A'..'Z' }
        return when {
            letters.isEmpty() -> text
            letters.all { it.isUpperCase() } && (letters.length > 1 || englishStartedWithCapsLock) ->
                text.uppercase(Locale.ROOT)
            letters.first().isUpperCase() && letters.drop(1).all { it.isLowerCase() } ->
                text.lowercase(Locale.ROOT).replaceFirstChar { it.uppercaseChar() }
            text.startsWith(input, ignoreCase = true) -> input + text.drop(input.length)
            else -> text
        }
    }

    /** Configures search options */
    fun setImeOption(option: String, value: Boolean) {
        Rime.setRimeOption(option, value)
    }

    /** Resolves Rime key code mapping */
    private fun getRimeKeycodeByName(name: String) : Int {
        return Rime.getRimeKeycodeByName(name)
    }

    private fun t9KeyCode(keyCode: Int): Int = when (keyCode) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_C -> '2'.code
        in KeyEvent.KEYCODE_D..KeyEvent.KEYCODE_F -> '3'.code
        in KeyEvent.KEYCODE_G..KeyEvent.KEYCODE_I -> '4'.code
        in KeyEvent.KEYCODE_J..KeyEvent.KEYCODE_L -> '5'.code
        in KeyEvent.KEYCODE_M..KeyEvent.KEYCODE_O -> '6'.code
        in KeyEvent.KEYCODE_P..KeyEvent.KEYCODE_S -> '7'.code
        in KeyEvent.KEYCODE_T..KeyEvent.KEYCODE_V -> '8'.code
        else -> '9'.code
    }

    fun setCharCase(charCase: Int) {
        this.charCase = charCase
    }

    val candidatePageNo: Int
        get() = currentCandidatePageNo

    val hasNextCandidatePage: Boolean
        get() = currentCandidatePageHasNext

    val rawPreedit: String
        get() = rawInput.ifEmpty { rawCompositionPreedit }

}
