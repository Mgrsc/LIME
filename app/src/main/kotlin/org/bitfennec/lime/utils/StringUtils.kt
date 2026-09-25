package org.bitfennec.lime.utils

object StringUtils {
    // Halfwidth and fullwidth character mapping:
    // 1) Halfwidth ASCII (excluding space) spans 33 (0x21) to 126 (0x7E);
    // 2) Fullwidth counterparts span 65281 (0xFF01) to 65374 (0xFF3E);
    // 3) Space: halfwidth is 32 (0x20), fullwidth is 12288 (0x3000);
    // 4) Period: halfwidth is 65377 (0xFF61), fullwidth is 12290 (0x3002);
    // 5) Offset for non-space characters is 65248 (65281 - 33 = 65248).
    const val SBC_SPACE = 12288 // Fullwidth space 12288
        .toChar()
    const val DBC_SPACE = 32 // Halfwidth space 32
        .toChar()
    const val SBC_PERIOD = 12290 // Fullwidth period
        .toChar()
    const val DBC_PERIOD = 65377 // Halfwidth period
        .toChar()
    const val UNICODE_START = 65278.toChar()
    const val UNICODE_END = 65374.toChar()
    const val DBC_SBC_STEP = 65248 // Fullwidth to halfwidth offset
        .toChar()

    // Fullwidth to halfwidth
    private fun sbc2dbc(src: Char): Char {
        return if (src == SBC_SPACE) {
            DBC_SPACE
        } else if (src == SBC_PERIOD) {
            DBC_PERIOD
        } else {
            if (src in UNICODE_START..UNICODE_END) {
                (src.code - DBC_SBC_STEP.code).toChar()
            } else src
        }
    }

    // Fullwidth to halfwidth
    fun sbc2dbcCase(src: String?): String? {
        if (src == null) {
            return null
        }
        val c = src.toCharArray()
        for (i in c.indices) {
            c[i] = sbc2dbc(c[i])
        }
        return String(c)
    }

    /**
     * Checks whether character is halfwidth symbol (excluding digits and letters).
     */
    fun isDBCSymbol(src: String?): Boolean {
        if (src == null || src.length > 1) {
            return false
        }
        val c = src[0]
        return c.code in 32..47 || c.code in 58..64 || c.code in 91..96 || c.code in 123..126
    }

    /**
     * Escapes standard JSON control characters: backslash, quotes, newlines, carriage returns, and tabs.
     */
    fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
