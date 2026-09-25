package org.bitfennec.lime.inputmethod.calc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NumberCalcEngineTest {

    @Test
    fun testBasicArithmeticPrecedence() {
        val res1 = NumberCalcEngine.calculate("1+2*3")
        assertTrue(res1 is CalcResult.Success)
        assertEquals("7", (res1 as CalcResult.Success).result)
        assertEquals("1+2*3=7", res1.fullEquation)

        val res2 = NumberCalcEngine.calculate("(1+2)*3")
        assertTrue(res2 is CalcResult.Success)
        assertEquals("9", (res2 as CalcResult.Success).result)
    }

    @Test
    fun testDecimalPrecisionWithoutFloatDrift() {
        val res = NumberCalcEngine.calculate("0.1+0.2")
        assertTrue(res is CalcResult.Success)
        assertEquals("0.3", (res as CalcResult.Success).result)

        val resSub = NumberCalcEngine.calculate("0.3-0.2")
        assertTrue(resSub is CalcResult.Success)
        assertEquals("0.1", (resSub as CalcResult.Success).result)
    }

    @Test
    fun testPercentageSemantics() {
        // Postfix standalone percentage
        val res1 = NumberCalcEngine.calculate("50%")
        assertTrue(res1 is CalcResult.Success)
        assertEquals("0.5", (res1 as CalcResult.Success).result)

        // Compound addition: 100 + 10% = 110
        val res2 = NumberCalcEngine.calculate("100+10%")
        assertTrue(res2 is CalcResult.Success)
        assertEquals("110", (res2 as CalcResult.Success).result)

        // Compound subtraction: 100 - 10% = 90
        val res3 = NumberCalcEngine.calculate("100-10%")
        assertTrue(res3 is CalcResult.Success)
        assertEquals("90", (res3 as CalcResult.Success).result)

        // Multiplicative percentage: 200 * 15% = 30
        val res4 = NumberCalcEngine.calculate("200*15%")
        assertTrue(res4 is CalcResult.Success)
        assertEquals("30", (res4 as CalcResult.Success).result)

        // Divisive percentage: 20 / 50% = 40
        val res5 = NumberCalcEngine.calculate("20/50%")
        assertTrue(res5 is CalcResult.Success)
        assertEquals("40", (res5 as CalcResult.Success).result)
    }

    @Test
    fun testDivisionByZeroReportsError() {
        val res = NumberCalcEngine.calculate("5/0")
        assertTrue(res is CalcResult.Error)
        assertEquals("除数不能为 0", (res as CalcResult.Error).message)

        val resParentheses = NumberCalcEngine.calculate("10/(5-5)")
        assertTrue(resParentheses is CalcResult.Error)
        assertEquals("除数不能为 0", (resParentheses as CalcResult.Error).message)
    }

    @Test
    fun testIncompleteSyntaxFallsBackToIdle() {
        // Half-written formulas should never popup annoying errors
        assertEquals(CalcResult.Idle, NumberCalcEngine.calculate("1+"))
        assertEquals(CalcResult.Idle, NumberCalcEngine.calculate("(1+2"))
        assertEquals(CalcResult.Idle, NumberCalcEngine.calculate("5*"))
        assertEquals(CalcResult.Idle, NumberCalcEngine.calculate("."))
        assertEquals(CalcResult.Idle, NumberCalcEngine.calculate("123")) // No operator
    }

    @Test
    fun testTokenNormalization() {
        // × and ÷ normalization
        val res = NumberCalcEngine.calculate("6÷2×4")
        assertTrue(res is CalcResult.Success)
        assertEquals("12", (res as CalcResult.Success).result)
    }

    @Test
    fun testTrailingEqualSignPreservation() {
        val res = NumberCalcEngine.calculate("1+2=")
        assertTrue(res is CalcResult.Success)
        val success = res as CalcResult.Success
        assertEquals("3", success.result)
        assertEquals("1+2=3", success.fullEquation)
    }

    @Test
    fun testExtractionFromSentenceEnd() {
        val res = NumberCalcEngine.calculate("Total: 50+20*2")
        assertTrue(res is CalcResult.Success)
        assertEquals("90", (res as CalcResult.Success).result)
    }

    @Test
    fun testTrailingWhitespaceDeleteLength() {
        val res1 = NumberCalcEngine.calculate("1+2 ")
        assertTrue(res1 is CalcResult.Success)
        val success1 = res1 as CalcResult.Success
        assertEquals("1+2", success1.expression)
        assertEquals("3", success1.result)
        assertEquals(4, success1.deleteLength) // "1+2 " is 4 chars

        val res2 = NumberCalcEngine.calculate("Total: 10+20%  ")
        assertTrue(res2 is CalcResult.Success)
        val success2 = res2 as CalcResult.Success
        assertEquals("10+20%", success2.expression)
        assertEquals("12", success2.result)
        assertEquals(8, success2.deleteLength) // "10+20%  " is 8 chars
    }

    @Test
    fun testContinuousDivisionAndMultiplicationPrecision() {
        val res1 = NumberCalcEngine.calculate("1/3*3")
        assertTrue(res1 is CalcResult.Success)
        assertEquals("1", (res1 as CalcResult.Success).result)

        val res2 = NumberCalcEngine.calculate("10/3*3")
        assertTrue(res2 is CalcResult.Success)
        assertEquals("10", (res2 as CalcResult.Success).result)
    }
}
