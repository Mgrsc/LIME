package org.bitfennec.lime.data.keyboard

/**
 * Unified operator key definitions shared between the left sidebar and candidate toolbar.
 */
enum class OpKey(
    val token: String,
    val displayLabel: String,
) {
    PLUS("+", "+"),
    MINUS("-", "-"),
    MULTIPLY("*", "×"),
    DIVIDE("/", "÷"),
    PERCENT("%", "%"),
    EQUAL("=", "="),
    DOT(".", "."),
    LEFT_PAREN("(", "("),
    RIGHT_PAREN(")", ")"),
    COMMA(",", ",");

    companion object {
        val DEFAULT_TOOL_BAR_KEYS = listOf(
            EQUAL, PLUS, MINUS, MULTIPLY, DIVIDE, PERCENT, DOT, COMMA, LEFT_PAREN, RIGHT_PAREN
        )

        fun normalizeTokens(input: String): String {
            return input
                .replace('×', '*')
                .replace('÷', '/')
                .replace('（', '(')
                .replace('）', ')')
                .replace('—', '-')
                .replace('–', '-')
                .replace('＝', '=')
        }
    }
}
