package org.bitfennec.lime.inputmethod.calc

import org.bitfennec.lime.R
import org.bitfennec.lime.application.Launcher
import org.bitfennec.lime.data.keyboard.OpKey
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

data class ExpressionSpan(
    val raw: String,
    val start: Int,
    val deleteLength: Int,
)

sealed interface CalcResult {
    data class Success(
        val expression: String,
        val result: String,
        val fullEquation: String,
        val deleteLength: Int,
    ) : CalcResult

    data class Error(val message: String) : CalcResult
    object Idle : CalcResult

    companion object {
        const val COMMENT_ERROR = "error"
    }
}

/**
 * Lightweight numeric keyboard calculator engine using MathContext.DECIMAL64 and Casio-standard percentage semantics.
 */
object NumberCalcEngine {
    private val MC = MathContext.DECIMAL64
    private val ONE_HUNDRED = BigDecimal("100")
    private val OPERATOR_CHARS = charArrayOf('+', '-', '*', '/', '%', '×', '÷')

    fun hasOperator(input: String): Boolean {
        return input.any { it in OPERATOR_CHARS }
    }

    fun getDivideByZeroMessage(): String {
        return runCatching {
            Launcher.instance.context.getString(R.string.calc_err_divide_by_zero)
        }.getOrDefault("除数不能为 0")
    }

    /**
     * Extracts a continuous arithmetic formula and its span from the trailing end of the text before cursor.
     * Non-formula prefix text before the formula is preserved; deleteLength only spans from formula start to end of text.
     */
    fun extractExpressionSpan(text: String): ExpressionSpan? {
        if (text.isEmpty()) return null
        var i = text.length - 1
        while (i >= 0 && text[i].isWhitespace()) {
            i--
        }
        val end = i + 1
        while (i >= 0) {
            val c = text[i]
            if (c.isDigit() || c in "+-*/×÷%().=" || c.isWhitespace() || c in "—–") {
                i--
            } else {
                break
            }
        }
        var start = i + 1
        while (start < end && text[start].isWhitespace()) {
            start++
        }
        if (start >= end) return null
        val raw = text.substring(start, end).trim()
        if (raw.isEmpty()) return null
        return ExpressionSpan(raw = raw, start = start, deleteLength = text.length - start)
    }

    fun calculate(textBeforeCursor: String): CalcResult {
        val span = extractExpressionSpan(textBeforeCursor) ?: return CalcResult.Idle
        val raw = span.raw
        if (raw.length > 64) return CalcResult.Idle
        val withoutEqual = raw.removeSuffix("=").trim()
        if (withoutEqual.isEmpty() || !hasOperator(withoutEqual)) return CalcResult.Idle

        val normalized = OpKey.normalizeTokens(withoutEqual)
        return try {
            val evaluated = Parser(normalized).parse()
            var plain = evaluated.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
            if (plain == "-0") plain = "0"

            val fullEq = if (raw.endsWith("=")) "$raw$plain" else "$raw=$plain"
            CalcResult.Success(
                expression = raw,
                result = plain,
                fullEquation = fullEq,
                deleteLength = span.deleteLength
            )
        } catch (e: ArithmeticException) {
            CalcResult.Error(e.message ?: getDivideByZeroMessage())
        } catch (_: Exception) {
            // Incomplete syntax like (1+2 or 5* falls back to Idle without error annoyance
            CalcResult.Idle
        }
    }

    private class Parser(private val src: String) {
        private var pos = 0
        private val len = src.length

        fun parse(): BigDecimal {
            skipWhitespace()
            if (pos >= len) throw IllegalArgumentException("Empty expression")
            val res = parseExpression()
            skipWhitespace()
            if (pos < len) {
                throw IllegalArgumentException("Unexpected character at $pos")
            }
            return res
        }

        private data class TermResult(
            val value: BigDecimal,
            val isDirectPercent: Boolean,
            val percentRate: BigDecimal = BigDecimal.ZERO
        )

        private fun parseExpression(): BigDecimal {
            val firstTerm = parseTerm()
            var left = firstTerm.value
            while (true) {
                skipWhitespace()
                if (pos < len && (src[pos] == '+' || src[pos] == '-')) {
                    val op = src[pos++]
                    val rightTerm = parseTerm()
                    if (rightTerm.isDirectPercent) {
                        // Casio-standard compound percentage: A + B% => A + A*(B/100), A - B% => A - A*(B/100)
                        val delta = left.multiply(rightTerm.percentRate, MC).divide(ONE_HUNDRED, MC)
                        left = if (op == '+') left.add(delta, MC) else left.subtract(delta, MC)
                    } else {
                        left = if (op == '+') left.add(rightTerm.value, MC) else left.subtract(rightTerm.value, MC)
                    }
                } else {
                    break
                }
            }
            return left
        }

        private fun parseTerm(): TermResult {
            var firstFactor = parseFactor()
            var isSingleFactor = true
            while (true) {
                skipWhitespace()
                if (pos < len && (src[pos] == '*' || src[pos] == '/')) {
                    isSingleFactor = false
                    val op = src[pos++]
                    val nextFactor = parseFactor()
                    val rightVal = nextFactor.value
                    firstFactor = if (op == '*') {
                        TermResult(firstFactor.value.multiply(rightVal, MC), isDirectPercent = false)
                    } else {
                        if (rightVal.compareTo(BigDecimal.ZERO) == 0) {
                            throw ArithmeticException(getDivideByZeroMessage())
                        }
                        TermResult(firstFactor.value.divide(rightVal, MC), isDirectPercent = false)
                    }
                } else {
                    break
                }
            }
            return if (isSingleFactor) firstFactor else firstFactor.copy(isDirectPercent = false)
        }

        private fun parseFactor(): TermResult {
            skipWhitespace()
            val primary = parsePrimary()
            skipWhitespace()
            if (pos < len && src[pos] == '%') {
                pos++
                // Postfix percentage: B% => B / 100
                val percentVal = primary.divide(ONE_HUNDRED, MC)
                return TermResult(value = percentVal, isDirectPercent = true, percentRate = primary)
            }
            return TermResult(value = primary, isDirectPercent = false)
        }

        private fun parsePrimary(): BigDecimal {
            skipWhitespace()
            if (pos >= len) throw IllegalArgumentException("Unexpected end of expression")

            // Unary + / -
            if (src[pos] == '+') {
                pos++
                return parsePrimary()
            }
            if (src[pos] == '-') {
                pos++
                return parsePrimary().negate()
            }

            // Parentheses
            if (src[pos] == '(') {
                pos++
                val inner = parseExpression()
                skipWhitespace()
                if (pos < len && src[pos] == ')') {
                    pos++
                    return inner
                }
                throw IllegalArgumentException("Unclosed parenthesis")
            }

            // Number literal
            if (src[pos].isDigit() || (src[pos] == '.' && pos + 1 < len && src[pos + 1].isDigit())) {
                val start = pos
                var hasDot = false
                while (pos < len) {
                    val c = src[pos]
                    if (c.isDigit()) {
                        pos++
                    } else if (c == '.' && !hasDot) {
                        hasDot = true
                        pos++
                    } else {
                        break
                    }
                }
                val numStr = src.substring(start, pos)
                return BigDecimal(numStr)
            }

            throw IllegalArgumentException("Unexpected char '${src[pos]}' at $pos")
        }

        private fun skipWhitespace() {
            while (pos < len && src[pos].isWhitespace()) {
                pos++
            }
        }
    }
}
