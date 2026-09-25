package org.bitfennec.lime.service

import android.content.SharedPreferences
import org.bitfennec.lime.core.CandidateListItem
import org.bitfennec.lime.inputmethod.EngineEvent
import org.bitfennec.lime.inputmethod.EnginePipeline
import org.bitfennec.lime.prefs.AppPrefs
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import java.lang.reflect.Field

@OptIn(ExperimentalCoroutinesApi::class)
class HandwritingSessionManagementTest {

    private lateinit var service: ImeService

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        AppPrefs.init(InMemorySharedPreferences())
        service = ImeService()
        service.inputConnectionOverride = createMockInputConnection(commitSuccess = true)
        DecodingInfo.reset()
    }

    private fun createMockInputConnection(commitSuccess: Boolean = true): android.view.inputmethod.InputConnection {
        return java.lang.reflect.Proxy.newProxyInstance(
            android.view.inputmethod.InputConnection::class.java.classLoader,
            arrayOf(android.view.inputmethod.InputConnection::class.java),
        ) { _, method, _ ->
            when {
                method.name == "commitText" -> commitSuccess
                method.returnType == java.lang.Boolean.TYPE -> true
                method.returnType == java.lang.Integer.TYPE -> 0
                else -> null
            }
        } as android.view.inputmethod.InputConnection
    }

    @After
    fun tearDown() {
        if (::service.isInitialized) {
            service.cancelModalSession(ImeService.ModalInputMode.HANDWRITING)
        }
        DecodingInfo.reset()
        org.bitfennec.lime.core.runtime.AiModuleManager.invalidateCache()
        Dispatchers.resetMain()
    }

    private fun setAiModuleHandwritingReady(ready: Boolean) {
        val field = org.bitfennec.lime.core.runtime.AiModuleManager::class.java.getDeclaredField("cachedHwReady").apply {
            isAccessible = true
        }
        field.set(org.bitfennec.lime.core.runtime.AiModuleManager, ready)
    }

    private fun setModalSession(id: Long, editorSessionId: Long, mode: ImeService.ModalInputMode) {
        val modalSessionClass = Class.forName("org.bitfennec.lime.service.ImeService\$ModalSession")
        val constructor = modalSessionClass.getDeclaredConstructor(
            Long::class.javaPrimitiveType,
            Long::class.javaPrimitiveType,
            ImeService.ModalInputMode::class.java,
        ).apply { isAccessible = true }
        val sessionInstance = constructor.newInstance(id, editorSessionId, mode)

        val field: Field = ImeService::class.java.getDeclaredField("modalSession").apply {
            isAccessible = true
        }
        field.set(service, sessionInstance)
    }

    private fun setStylusHandwritingReusedSession(value: Boolean) {
        val field: Field = ImeService::class.java.getDeclaredField("stylusHandwritingReusedSession").apply {
            isAccessible = true
        }
        field.set(service, value)
    }

    @Test
    fun testCurrentModalSessionIdReturnsNullWhenNoSession() {
        assertNull(
            "currentModalSessionId must return null when no session is active",
            service.currentModalSessionId(ImeService.ModalInputMode.HANDWRITING),
        )
        assertFalse(
            "isModalModeActive must be false when no session is active",
            service.isModalModeActive(ImeService.ModalInputMode.HANDWRITING),
        )
    }

    @Test
    fun testPublishHandwritingCandidatesRejectsWhenNoActiveSession() {
        val cands = arrayOf(CandidateListItem("", "测试"))
        service.publishHandwritingCandidates(100L, cands)

        assertFalse(
            "DecodingInfo must not have external candidates when publication is rejected",
            DecodingInfo.hasExternalCandidateSource,
        )
        assertNull(
            "Publication must not resurrect or create a handwriting session",
            service.currentModalSessionId(ImeService.ModalInputMode.HANDWRITING),
        )
    }

    @Test
    fun testPublishHandwritingCandidatesRejectsStaleSessionId() {
        val editorId = EnginePipeline.currentSessionId
        setModalSession(id = 200L, editorSessionId = editorId, mode = ImeService.ModalInputMode.HANDWRITING)

        // Stale callback with old sessionId 199L arrives
        val cands = arrayOf(CandidateListItem("", "迟到候选"))
        service.publishHandwritingCandidates(199L, cands)

        assertFalse(
            "Late callback with mismatched session ID must be rejected",
            DecodingInfo.hasExternalCandidateSource,
        )
    }

    @Test
    fun testPublishHandwritingCandidatesRejectsCrossEditorSession() {
        val oldEditorId = EnginePipeline.currentSessionId
        setModalSession(id = 300L, editorSessionId = oldEditorId, mode = ImeService.ModalInputMode.HANDWRITING)

        // Editor changes
        EnginePipeline.invalidateSession()
        val newEditorId = EnginePipeline.currentSessionId

        // Late callback from old editor arrives
        val cands = arrayOf(CandidateListItem("", "跨编辑器"))
        service.publishHandwritingCandidates(300L, cands)

        assertFalse(
            "Candidates from a previous editor session must be dropped",
            DecodingInfo.hasExternalCandidateSource,
        )
    }

    @Test
    fun testPublishHandwritingCandidatesAcceptsMatchingSession() {
        val editorId = EnginePipeline.currentSessionId
        setModalSession(id = 400L, editorSessionId = editorId, mode = ImeService.ModalInputMode.HANDWRITING)

        val cands = arrayOf(CandidateListItem("", "正解"))
        service.publishHandwritingCandidates(400L, cands)

        assertTrue(
            "Candidates must be accepted when modal and editor session IDs match",
            DecodingInfo.hasExternalCandidateSource,
        )
        assertEquals(
            listOf(CandidateListItem("", "正解")),
            DecodingInfo.candidatesFlow.value,
        )
    }

    @Test
    fun testCancelModalSessionClearsStateAndCandidates() {
        val editorId = EnginePipeline.currentSessionId
        setModalSession(id = 500L, editorSessionId = editorId, mode = ImeService.ModalInputMode.HANDWRITING)
        DecodingInfo.cacheCandidates(arrayOf(CandidateListItem("", "待清空")), modalSessionId = 500L)

        assertTrue(DecodingInfo.hasExternalCandidateSource)
        assertTrue(service.isModalModeActive(ImeService.ModalInputMode.HANDWRITING))

        // Exit handwriting
        service.cancelModalSession(ImeService.ModalInputMode.HANDWRITING)

        assertNull("modalSession must be null after cancel", service.currentModalSessionId(ImeService.ModalInputMode.HANDWRITING))
        assertFalse("isModalModeActive must be false after cancel", service.isModalModeActive(ImeService.ModalInputMode.HANDWRITING))
        assertFalse("DecodingInfo candidates must be cleared", DecodingInfo.hasExternalCandidateSource)
    }

    @Test
    fun testContinuousHandwritingRetainsSessionAfterCommit() {
        val editorId = EnginePipeline.currentSessionId
        setModalSession(id = 600L, editorSessionId = editorId, mode = ImeService.ModalInputMode.HANDWRITING)
        DecodingInfo.cacheCandidates(arrayOf(CandidateListItem("", "首字")), modalSessionId = 600L)

        // Commit candidate (simulated without crash since inputConnection is null in mock)
        val committed = service.commitModalText(600L, "首字")
        assertTrue("commitModalText must return true", committed)

        // Session must be preserved for subsequent handwriting strokes
        assertEquals(
            600L,
            service.currentModalSessionId(ImeService.ModalInputMode.HANDWRITING),
        )
        assertTrue(
            service.isModalModeActive(ImeService.ModalInputMode.HANDWRITING),
        )
        // Candidates for the committed stroke should be cleared
        assertFalse(DecodingInfo.hasExternalCandidateSource)
    }

    @Test
    fun testCommitModalTextReturnsFalseWhenInputConnectionFails() {
        val editorId = EnginePipeline.currentSessionId
        setModalSession(id = 601L, editorSessionId = editorId, mode = ImeService.ModalInputMode.HANDWRITING)
        service.inputConnectionOverride = createMockInputConnection(commitSuccess = false)

        val committed = service.commitModalText(601L, "失败")
        assertFalse("commitModalText must return false when input connection fails", committed)
    }

    @Test
    fun testCommitModalTextReturnsFalseWhenInputConnectionNull() {
        val editorId = EnginePipeline.currentSessionId
        setModalSession(id = 602L, editorSessionId = editorId, mode = ImeService.ModalInputMode.HANDWRITING)
        service.inputConnectionOverride = null

        val committed = service.commitModalText(602L, "无连接")
        assertFalse("commitModalText must return false when input connection is null", committed)
    }

    @Test
    fun testCommitModalTextRejectsSessionIdMismatch() {
        val editorId = EnginePipeline.currentSessionId
        setModalSession(id = 603L, editorSessionId = editorId, mode = ImeService.ModalInputMode.HANDWRITING)

        val committed = service.commitModalText(999L, "错位")
        assertFalse("commitModalText must reject mismatched modalSessionId", committed)
    }

    @Test
    fun testCommitModalTextRejectsCrossEditorSession() {
        val oldEditorId = EnginePipeline.currentSessionId
        setModalSession(id = 604L, editorSessionId = oldEditorId, mode = ImeService.ModalInputMode.HANDWRITING)
        EnginePipeline.invalidateSession()

        val committed = service.commitModalText(604L, "跨编辑器")
        assertFalse("commitModalText must reject stale editorSessionId", committed)
    }

    @Test
    fun testVoiceModalSessionQueuesClearCompositionAndActivates() {
        val field = org.bitfennec.lime.core.runtime.AiModuleManager::class.java.getDeclaredField("cachedVoiceReady").apply {
            isAccessible = true
        }
        field.set(org.bitfennec.lime.core.runtime.AiModuleManager, true)

        var readySessionId = 0L
        val accepted = service.requestModalSession(
            mode = ImeService.ModalInputMode.VOICE,
            onReady = { id -> readySessionId = id },
        )
        assertTrue(accepted)

        // Session must be pending until CompositionCleared arrives
        assertNull(service.currentModalSessionId(ImeService.ModalInputMode.VOICE))

        val pendingField = ImeService::class.java.getDeclaredField("pendingModalSession").apply {
            isAccessible = true
        }
        val pending = pendingField.get(service)
        val requestIdField = pending?.javaClass?.getDeclaredField("requestId")?.apply { isAccessible = true }
        val requestId = requestIdField?.get(pending) as Long

        val activateMethod = ImeService::class.java.getDeclaredMethod(
            "activateModalSession",
            EngineEvent.CompositionCleared::class.java,
        ).apply { isAccessible = true }

        val event = EngineEvent.CompositionCleared(
            modalRequestId = requestId,
            sessionId = EnginePipeline.currentSessionId,
        )
        activateMethod.invoke(service, event)

        val activeSessionId = service.currentModalSessionId(ImeService.ModalInputMode.VOICE)
        assertTrue("Active session ID must be non-null and positive", (activeSessionId ?: 0L) > 0L)
        assertEquals(activeSessionId, readySessionId)
        assertTrue(service.isModalModeActive(ImeService.ModalInputMode.VOICE))

        // Commit voice text
        val committed = service.commitModalText(readySessionId, "语音识别文字")
        assertTrue(committed)
        // Voice session remains active across streaming segments
        assertTrue(service.isModalModeActive(ImeService.ModalInputMode.VOICE))
    }

    @Test
    fun testStylusHandwritingReusedSessionPreservedOnFinish() {
        val editorId = EnginePipeline.currentSessionId
        setModalSession(id = 700L, editorSessionId = editorId, mode = ImeService.ModalInputMode.HANDWRITING)
        setStylusHandwritingReusedSession(true)

        // Ending stylus handwriting when session was reused must preserve the original handwriting session
        service.onFinishStylusHandwriting()

        assertEquals(
            700L,
            service.currentModalSessionId(ImeService.ModalInputMode.HANDWRITING),
        )
        assertTrue(
            "Session must remain active for regular handwriting",
            service.isModalModeActive(ImeService.ModalInputMode.HANDWRITING),
        )
    }

    @Test
    fun testStylusHandwritingExclusiveSessionCancelledOnFinish() {
        val editorId = EnginePipeline.currentSessionId
        setModalSession(id = 800L, editorSessionId = editorId, mode = ImeService.ModalInputMode.HANDWRITING)
        setStylusHandwritingReusedSession(false)

        // Ending stylus handwriting for an exclusive session must cancel the handwriting session
        service.onFinishStylusHandwriting()

        assertNull(
            "Exclusive session must be cancelled on finish",
            service.currentModalSessionId(ImeService.ModalInputMode.HANDWRITING),
        )
        assertFalse(
            "Session must not be active",
            service.isModalModeActive(ImeService.ModalInputMode.HANDWRITING),
        )
    }

    @Test
    fun testPendingModalSessionChainsOnReadyCallbacks() {
        setAiModuleHandwritingReady(true)
        var firstCallbackSessionId = 0L
        var secondCallbackSessionId = 0L

        // First request creates a pending session
        val firstAccepted = service.requestModalSession(
            mode = ImeService.ModalInputMode.HANDWRITING,
            onReady = { id -> firstCallbackSessionId = id },
        )
        assertTrue(firstAccepted)

        // Second request while first is still pending must chain onReady callback
        val secondAccepted = service.requestModalSession(
            mode = ImeService.ModalInputMode.HANDWRITING,
            onReady = { id -> secondCallbackSessionId = id },
        )
        assertTrue(secondAccepted)

        // Simulate engine composition cleared event activating the session
        val pendingField = ImeService::class.java.getDeclaredField("pendingModalSession").apply {
            isAccessible = true
        }
        val pending = pendingField.get(service)
        val requestIdField = pending?.javaClass?.getDeclaredField("requestId")?.apply { isAccessible = true }
        val requestId = requestIdField?.get(pending) as Long

        val activateMethod = ImeService::class.java.getDeclaredMethod(
            "activateModalSession",
            EngineEvent.CompositionCleared::class.java,
        ).apply { isAccessible = true }

        val event = EngineEvent.CompositionCleared(
            modalRequestId = requestId,
            sessionId = EnginePipeline.currentSessionId,
        )
        activateMethod.invoke(service, event)

        val activeSessionId = service.currentModalSessionId(ImeService.ModalInputMode.HANDWRITING)
        assertTrue("Active session ID must be non-null and positive", (activeSessionId ?: 0L) > 0L)
        assertEquals("First callback must receive active session id", activeSessionId, firstCallbackSessionId)
        assertEquals("Second callback must receive active session id", activeSessionId, secondCallbackSessionId)
    }

    @Test
    fun testCancelModalSessionByModeInvokesPendingOnCancelled() {
        setAiModuleHandwritingReady(true)
        var cancelled = false
        val accepted = service.requestModalSession(
            mode = ImeService.ModalInputMode.HANDWRITING,
            onReady = {},
            onCancelled = { cancelled = true },
        )
        assertTrue(accepted)

        // Cancel by mode must invoke onCancelled
        service.cancelModalSession(ImeService.ModalInputMode.HANDWRITING)

        assertTrue("cancelModalSession by mode must invoke onCancelled callback", cancelled)
        assertFalse(service.isModalModeActive(ImeService.ModalInputMode.HANDWRITING))
    }

    @Test
    fun testCancelModalSessionByIdInvokesPendingOnCancelled() {
        setAiModuleHandwritingReady(true)
        var cancelled = false
        val accepted = service.requestModalSession(
            mode = ImeService.ModalInputMode.HANDWRITING,
            onReady = {},
            onCancelled = { cancelled = true },
        )
        assertTrue(accepted)

        val pendingField = ImeService::class.java.getDeclaredField("pendingModalSession").apply {
            isAccessible = true
        }
        val pending = pendingField.get(service)
        val requestIdField = pending?.javaClass?.getDeclaredField("requestId")?.apply { isAccessible = true }
        val requestId = requestIdField?.get(pending) as Long

        // Cancel by request id must invoke onCancelled
        service.cancelModalSession(requestId)

        assertTrue("cancelModalSession by id must invoke onCancelled callback", cancelled)
        assertFalse(service.isModalModeActive(ImeService.ModalInputMode.HANDWRITING))
    }

    @Test
    fun testDiscardHandwritingCandidatesDoesNotDiscardExternalCandidatesWithoutSession() {
        // Given external candidates (such as calculator) when no handwriting modal session exists
        DecodingInfo.cacheCandidates(arrayOf(CandidateListItem("", "123")), associate = true)
        assertTrue(DecodingInfo.hasExternalCandidateSource)

        // When discardHandwritingCandidates is invoked without an active handwriting session
        val discarded = service.discardHandwritingCandidates()

        // Then it must return false and external candidates must remain untouched
        assertFalse("Must return false when no handwriting session is active", discarded)
        assertTrue("External candidates must remain untouched", DecodingInfo.hasExternalCandidateSource)
    }

    @Test
    fun testDiscardHandwritingCandidatesClearsCandidatesWhenSessionActive() {
        // Given an active handwriting modal session with handwriting candidates
        val editorId = EnginePipeline.currentSessionId
        setModalSession(id = 800L, editorSessionId = editorId, mode = ImeService.ModalInputMode.HANDWRITING)
        DecodingInfo.cacheCandidates(arrayOf(CandidateListItem("", "手写词")), modalSessionId = 800L)
        assertTrue(DecodingInfo.hasExternalCandidateSource)

        // When discardHandwritingCandidates is invoked with active handwriting session
        val discardedWithSession = service.discardHandwritingCandidates()

        // Then it must return true and handwriting candidates must be cleared
        assertTrue("Must return true when handwriting session is active", discardedWithSession)
        assertFalse("Handwriting candidates must be cleared", DecodingInfo.hasExternalCandidateSource)
    }

    private class InMemorySharedPreferences : SharedPreferences {
        private val map = mutableMapOf<String, Any?>()

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = map[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
            @Suppress("UNCHECKED_CAST") (map[key] as? MutableSet<String> ?: defValues)
        override fun getInt(key: String?, defValue: Int): Int = map[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = map[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = map[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = map[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = map.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor(map)
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

        private class Editor(private val target: MutableMap<String, Any?>) : SharedPreferences.Editor {
            private val pending = mutableMapOf<String, Any?>()
            private var clear = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply { pending[key!!] = values }
            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply { pending[key!!] = value }
            override fun remove(key: String?): SharedPreferences.Editor = apply { pending[key!!] = this }
            override fun clear(): SharedPreferences.Editor = apply { clear = true }
            override fun commit(): Boolean { apply(); return true }
            override fun apply() {
                if (clear) target.clear()
                for ((k, v) in pending) {
                    if (v === this) target.remove(k) else target[k] = v
                }
            }
        }
    }
}
