package org.bitfennec.lime.inputmethod

import kotlinx.coroutines.runBlocking
import org.bitfennec.lime.application.CustomConstant
import org.bitfennec.lime.core.CandidateListItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnginePipelineTest {

    @Test
    fun testEngineStateEqualityAndImmutability() {
        val state1 = EngineState(
            candidates = listOf(CandidateListItem("pinyin", "候选")),
            showComposition = "hou'xuan",
            prefixs = listOf("hou", "xuan"),
            isFinish = false,
            isAssociate = false,
            currentSchema = "pinyin",
            charCase = 0
        )

        val state2 = EngineState(
            candidates = listOf(CandidateListItem("pinyin", "候选")),
            showComposition = "hou'xuan",
            prefixs = listOf("hou", "xuan"),
            isFinish = false,
            isAssociate = false,
            currentSchema = "pinyin",
            charCase = 0
        )

        assertEquals(state1, state2)
        assertEquals(state1.hashCode(), state2.hashCode())

        val state3 = state1.copy(isFinish = true)
        assertNotEquals(state1, state3)
    }

    @Test
    fun testEngineStateDefaultValues() {
        val defaultState = EngineState()
        assertTrue(defaultState.candidates.isEmpty())
        assertEquals("", defaultState.showComposition)
        assertTrue(defaultState.prefixs.isEmpty())
        assertTrue(defaultState.isFinish)
        assertFalse(defaultState.isAssociate)
        assertEquals("", defaultState.currentSchema)
        assertEquals(0, defaultState.charCase)
    }

    @Test
    fun testEngineEventsDataContracts() {
        val commitEvent = EngineEvent.CommitText(0L, "你好")
        assertEquals("你好", commitEvent.text)

        val snapshotEvent = EngineEvent.ApplyRimeSnapshot(0L, "你", "hao", 7L)
        assertEquals("你", snapshotEvent.committedText)
        assertEquals("hao", snapshotEvent.composingText)
        assertEquals(7L, snapshotEvent.sequence)

        val keyEvent = EngineEvent.SendKeyEvent(0L, 66)
        assertEquals(66, keyEvent.keyCode)
    }

    @Test
    fun testEngineActionDispatch() {
        EnginePipeline.send(EngineAction.Reset())
        val currentState = EnginePipeline.stateFlow.value
        assertTrue(currentState.candidates.isEmpty())
    }

    @Test
    fun testEngineEventsCarrySessionIdentity() {
        val event = EngineEvent.CommitText(42L, "text")
        assertEquals(42L, event.sessionId)
    }

    @Test
    fun testSessionInvalidationChangesIdentity() {
        val first = EnginePipeline.beginSession()
        val second = EnginePipeline.invalidateSession()
        assertTrue(second > first)
    }

    @Test
    fun testRestartForSameEditorKeepsSessionIdentity() {
        val sessionKey = EditorSessionKey("org.example.editor", 7, 1, "private")

        val first = EnginePipeline.beginSession(sessionKey)
        val restarted = EnginePipeline.beginSession(sessionKey, restarting = true)

        assertEquals(first, restarted)
    }

    @Test
    fun testNewEditorStartsNewSession() {
        val first = EnginePipeline.beginSession(EditorSessionKey("org.example.first", 7, 1, ""))
        val second = EnginePipeline.beginSession(EditorSessionKey("org.example.second", 7, 1, ""))

        assertTrue(second > first)
    }

    @Test
    fun testExecuteExclusiveBarrier() = runBlocking {
        val result = EnginePipeline.executeExclusive {
            "exclusive_done"
        }
        assertEquals("exclusive_done", result)
    }

    @Test
    fun testEditorCompositionUsesRawTextForChineseInputConnection() {
        val composition = RimeEngine.editorCompositionText(
            schema = CustomConstant.SCHEMA_ZH_QWERTY,
            rawInput = "gm",
            showComposition = "g'm",
            rawCompositionPreedit = "gm",
        )

        assertEquals("gm", composition)
        assertEquals("想着va", RimeEngine.editorCompositionText(
            schema = CustomConstant.SCHEMA_ZH_QWERTY,
            rawInput = "xiangzheva",
            showComposition = "想着va",
            rawCompositionPreedit = "想着va",
            literalText = "想着va",
        ))
    }

    @Test
    fun testEditorCompositionKeepsEnglishPreedit() {
        val composition = RimeEngine.editorCompositionText(
            schema = CustomConstant.SCHEMA_EN,
            rawInput = "gmail",
            showComposition = "gmail",
            rawCompositionPreedit = "gmail",
        )

        assertEquals("gmail", composition)
    }

    @Test
    fun testEditorCompositionForChineseT9() {
        // Pure digits should not leak into editor
        assertEquals("", RimeEngine.editorCompositionText(
            schema = CustomConstant.SCHEMA_ZH_T9,
            rawInput = "64426",
            showComposition = "64426",
            rawCompositionPreedit = "64426",
        ))

        // Segmented digits should not leak into editor
        assertEquals("", RimeEngine.editorCompositionText(
            schema = CustomConstant.SCHEMA_ZH_T9,
            rawInput = "64'426",
            showComposition = "64'426",
            rawCompositionPreedit = "64'426",
        ))

        // Partially locked letters with digits should not leak into editor
        assertEquals("", RimeEngine.editorCompositionText(
            schema = CustomConstant.SCHEMA_ZH_T9,
            rawInput = "64'426",
            showComposition = "ni'426",
            rawCompositionPreedit = "64'426",
        ))

        // Confirmed pure pinyin letters should be mirrored as clean pinyin letters
        assertEquals("nihao", RimeEngine.editorCompositionText(
            schema = CustomConstant.SCHEMA_ZH_T9,
            rawInput = "64'426",
            showComposition = "ni'hao",
            rawCompositionPreedit = "64'426",
        ))

        // Empty composition should return empty string
        assertEquals("", RimeEngine.editorCompositionText(
            schema = CustomConstant.SCHEMA_ZH_T9,
            rawInput = "64426",
            showComposition = "",
            rawCompositionPreedit = "",
        ))
    }
}
