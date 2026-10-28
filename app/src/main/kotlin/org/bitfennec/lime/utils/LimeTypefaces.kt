package org.bitfennec.lime.utils

import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.graphics.fonts.FontStyle
import org.bitfennec.lime.prefs.AppPrefs
import java.io.File

object LimeTypefaces {
    private const val WEIGHT_REGULAR = 400
    private const val WEIGHT_MEDIUM = 500
    private const val CJK_SC_TTC_INDEX = 2
    private const val CJK_TC_TTC_INDEX = 3

    private val LATIN_FONT_PAIRS = listOf(
        "/system/fonts/Roboto-Regular.ttf" to "/system/fonts/Roboto-Medium.ttf",
        "/system/fonts/RobotoStatic-Regular.ttf" to "/system/fonts/Roboto-Medium.ttf",
        "/system/fonts/SourceSansPro-Regular.ttf" to "/system/fonts/SourceSansPro-SemiBold.ttf"
    )

    private val CJK_FONT_PATHS = listOf(
        "/system/fonts/NotoSansCJK-Regular.ttc",
        "/system/fonts/DroidSansFallback.ttf"
    )

    private const val EMOJI_FONT_PATH = "/system/fonts/NotoColorEmoji.ttf"

    private val fallbackRegular: Typeface by lazy {
        Typeface.create("sans-serif", Typeface.NORMAL)
    }

    private val fallbackMedium: Typeface by lazy {
        Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private val scRegular: Typeface by lazy {
        buildCleanTypeface(WEIGHT_REGULAR, CJK_SC_TTC_INDEX) ?: fallbackRegular
    }

    private val scMedium: Typeface by lazy {
        buildCleanTypeface(WEIGHT_MEDIUM, CJK_SC_TTC_INDEX) ?: fallbackMedium
    }

    private val tcRegular: Typeface by lazy {
        buildCleanTypeface(WEIGHT_REGULAR, CJK_TC_TTC_INDEX) ?: fallbackRegular
    }

    private val tcMedium: Typeface by lazy {
        buildCleanTypeface(WEIGHT_MEDIUM, CJK_TC_TTC_INDEX) ?: fallbackMedium
    }

    val regular: Typeface
        get() = if (isTraditionalChinese()) tcRegular else scRegular

    val medium: Typeface
        get() = if (isTraditionalChinese()) tcMedium else scMedium

    private fun isTraditionalChinese(): Boolean =
        runCatching { AppPrefs.getInstance().input.chineseFanTi.getValue() }.getOrDefault(false)

    private fun buildCleanTypeface(
        targetWeight: Int,
        cjkIndex: Int
    ): Typeface? = runCatching {
        val latinFamily = buildLatinFontFamily() ?: return null
        val builder = Typeface.CustomFallbackBuilder(latinFamily)
        buildCjkFontFamily(cjkIndex)?.let {
            builder.addCustomFallback(it)
        }
        buildSingleFontFamily(EMOJI_FONT_PATH)?.let {
            builder.addCustomFallback(it)
        }
        builder.setStyle(FontStyle(targetWeight, FontStyle.FONT_SLANT_UPRIGHT)).build()
    }.getOrNull()

    private fun buildLatinFontFamily(): FontFamily? {
        for ((regularPath, mediumPath) in LATIN_FONT_PAIRS) {
            val regularFile = File(regularPath)
            if (!isCleanSystemFontFile(regularFile)) continue
            val mediumFile = File(mediumPath).takeIf { isCleanSystemFontFile(it) } ?: regularFile
            val family = runCatching {
                val regularFont = buildFontWithWeight(regularFile, WEIGHT_REGULAR, 0)
                val mediumFont = buildFontWithWeight(mediumFile, WEIGHT_MEDIUM, 0)
                FontFamily.Builder(regularFont).addFont(mediumFont).build()
            }.getOrNull()
            if (family != null) return family
        }
        return null
    }

    private fun buildCjkFontFamily(ttcIndex: Int): FontFamily? {
        for (path in CJK_FONT_PATHS) {
            val file = File(path)
            if (!isCleanSystemFontFile(file)) continue
            val family = runCatching {
                val regularFont = buildFontWithWeight(file, WEIGHT_REGULAR, ttcIndex)
                val mediumFont = buildFontWithWeight(file, WEIGHT_MEDIUM, ttcIndex)
                FontFamily.Builder(regularFont).addFont(mediumFont).build()
            }.getOrNull()
            if (family != null) return family
        }
        return null
    }

    private fun buildFontWithWeight(file: File, weight: Int, ttcIndex: Int): Font {
        val effectiveIndex = if (file.name.endsWith(".ttc", ignoreCase = true)) ttcIndex else 0
        return runCatching {
            Font.Builder(file)
                .setTtcIndex(effectiveIndex)
                .setWeight(weight)
                .setSlant(FontStyle.FONT_SLANT_UPRIGHT)
                .setFontVariationSettings("'wght' $weight")
                .build()
        }.getOrElse {
            Font.Builder(file)
                .setTtcIndex(effectiveIndex)
                .setWeight(weight)
                .setSlant(FontStyle.FONT_SLANT_UPRIGHT)
                .build()
        }
    }

    private fun buildSingleFontFamily(path: String): FontFamily? {
        val file = File(path)
        if (!isCleanSystemFontFile(file)) return null
        return runCatching {
            val font = Font.Builder(file).build()
            FontFamily.Builder(font).build()
        }.getOrNull()
    }

    private fun isCleanSystemFontFile(file: File): Boolean {
        if (!file.exists() || !file.canRead()) return false
        val canonical = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        return !canonical.contains("vfonts", ignoreCase = true) &&
            !canonical.contains("VivoFont", ignoreCase = true)
    }
}
