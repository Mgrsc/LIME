package org.bitfennec.lime.keyboard

import org.bitfennec.lime.data.keyboard.OpKey
import org.bitfennec.lime.inputmethod.calc.CalcResult
import org.bitfennec.lime.inputmethod.calc.NumberCalcEngine
import org.bitfennec.lime.manager.InputModeSwitcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberKeyboardBehaviorTest {

    @Test
    fun testOpKeyNormalization() {
        assertEquals("1*2/3", OpKey.normalizeTokens("1×2÷3"))
        assertEquals("(1+2)", OpKey.normalizeTokens("（1+2）"))
        assertEquals("5-3", OpKey.normalizeTokens("5—3"))
        assertEquals("5-3", OpKey.normalizeTokens("5–3"))
    }

    @Test
    fun testNumberCalcEngineComplexEquations() {
        val r1 = NumberCalcEngine.calculate("100+10%")
        assertTrue(r1 is CalcResult.Success)
        assertEquals("110", (r1 as CalcResult.Success).result)
        assertEquals("100+10%=110", r1.fullEquation)

        val r2 = NumberCalcEngine.calculate("200-15%")
        assertTrue(r2 is CalcResult.Success)
        assertEquals("170", (r2 as CalcResult.Success).result)

        val r3 = NumberCalcEngine.calculate("1+2*3")
        assertTrue(r3 is CalcResult.Success)
        assertEquals("7", (r3 as CalcResult.Success).result)
        assertEquals("1+2*3=7", r3.fullEquation)

        // Trailing equal
        val r4 = NumberCalcEngine.calculate("1+2*3=")
        assertTrue(r4 is CalcResult.Success)
        assertEquals("7", (r4 as CalcResult.Success).result)
        assertEquals("1+2*3=7", r4.fullEquation)
    }

    @Test
    fun testDivisionByZeroYieldsNonClickableError() {
        val err = NumberCalcEngine.calculate("10/0")
        assertTrue(err is CalcResult.Error)
        assertEquals("除数不能为 0", (err as CalcResult.Error).message)
    }

    @Test
    fun testIncompleteFormulaStaysIdle() {
        assertEquals(CalcResult.Idle, NumberCalcEngine.calculate("1+"))
        assertEquals(CalcResult.Idle, NumberCalcEngine.calculate("(1+2"))
        assertEquals(CalcResult.Idle, NumberCalcEngine.calculate("5*"))
        assertEquals(CalcResult.Idle, NumberCalcEngine.calculate("123"))
    }

    @Test
    fun testPasswordFlagsBlockCalculator() {
        val pwdFlags = InputModeSwitcher.EditorFieldFlags(isPassword = true)
        assertTrue(pwdFlags.isPassword)
        assertTrue(pwdFlags.isPrivateOrSensitive)
        assertTrue(pwdFlags.isModalInputBlocked)
    }
}
