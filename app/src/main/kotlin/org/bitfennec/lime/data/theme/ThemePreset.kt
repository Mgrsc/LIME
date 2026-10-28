
package org.bitfennec.lime.data.theme

import android.content.Context
import androidx.core.content.ContextCompat
import org.bitfennec.lime.application.Launcher

object ThemePreset {

    fun createMonetLight(context: Context = Launcher.instance.context): Theme.Builtin {
        return try {
            val accent = ContextCompat.getColor(context, android.R.color.system_accent1_600)
            val bg = ContextCompat.getColor(context, android.R.color.system_neutral1_100)
            val bar = ContextCompat.getColor(context, android.R.color.system_neutral1_50)
            val keyBg = ContextCompat.getColor(context, android.R.color.system_neutral1_0)
            val funcBg = ContextCompat.getColor(context, android.R.color.system_neutral2_200)
            val textColor = ContextCompat.getColor(context, android.R.color.system_neutral1_900)
            Theme.Builtin(
                name = "MonetLight",
                isDark = false,
                keyboardResources = 0,
                barColor = bar,
                keyboardColor = bg,
                keyBackgroundColor = keyBg,
                keyTextColor = textColor,
                accentKeyBackgroundColor = accent,
                accentKeyTextColor = 0xffffffff.toInt(),
                keyPressHighlightColor = 0x1a000000,
                popupBackgroundColor = keyBg,
                functionKeyBackgroundColor = funcBg,
                functionKeyPressHighlightColor = 0x1a000000
            )
        } catch (_: Throwable) {
            MaterialLight.copy(name = "MonetLight")
        }
    }

    fun createMonetDark(context: Context = Launcher.instance.context): Theme.Builtin {
        return try {
            val accent = ContextCompat.getColor(context, android.R.color.system_accent1_200)
            val bg = ContextCompat.getColor(context, android.R.color.system_neutral1_900)
            val bar = ContextCompat.getColor(context, android.R.color.system_neutral1_900)
            val keyBg = ContextCompat.getColor(context, android.R.color.system_neutral1_700)
            val funcBg = ContextCompat.getColor(context, android.R.color.system_neutral2_800)
            val textColor = ContextCompat.getColor(context, android.R.color.system_neutral1_50)
            val darkAccentText = ContextCompat.getColor(context, android.R.color.system_accent1_900)
            Theme.Builtin(
                name = "MonetDark",
                isDark = true,
                keyboardResources = 0,
                barColor = bar,
                keyboardColor = bg,
                keyBackgroundColor = keyBg,
                keyTextColor = textColor,
                accentKeyBackgroundColor = accent,
                accentKeyTextColor = darkAccentText,
                keyPressHighlightColor = 0x26ffffff,
                popupBackgroundColor = keyBg,
                functionKeyBackgroundColor = funcBg,
                functionKeyPressHighlightColor = 0x22ffffff
            )
        } catch (_: Throwable) {
            MaterialDark.copy(name = "MonetDark")
        }
    }

    val MonetLight: Theme.Builtin get() = createMonetLight()
    val MonetDark: Theme.Builtin get() = createMonetDark()

    val MaterialLight = Theme.Builtin(
        name = "MaterialLight",
        isDark = false,
        keyboardResources = 0x0,
        keyboardColor = 0xffD4D7DE,
        barColor = 0xffDFE2E8,
        keyBackgroundColor = 0xffFFFFFF,
        keyTextColor = 0xff111827,
        accentKeyBackgroundColor = 0xff0F8A77,
        accentKeyTextColor = 0xffFFFFFF,
        keyPressHighlightColor = 0x1A000000,
        popupBackgroundColor = 0xffFFFFFF,
        functionKeyBackgroundColor = 0xffB8BEC9,
        functionKeyPressHighlightColor = 0x1A000000,
    )

    val MaterialDark = Theme.Builtin(
        name = "MaterialDark",
        isDark = true,
        keyboardResources = 0x0,
        barColor = 0xff1B1B1D,
        keyboardColor = 0xff1B1B1D,
        keyBackgroundColor = 0xff424246,
        keyTextColor = 0xffF5F5F7,
        accentKeyBackgroundColor = 0xff15A892,
        accentKeyTextColor = 0xffFFFFFF,
        keyPressHighlightColor = 0x26FFFFFF,
        popupBackgroundColor = 0xff525257,
        functionKeyBackgroundColor = 0xff2B2B2F,
        functionKeyPressHighlightColor = 0x22FFFFFF,
    )

    val PixelLight = Theme.Builtin(
        name = "PixelLight",
        isDark = false,
        keyboardResources = 0x0,
        barColor = 0xffEEF0F6,
        keyboardColor = 0xffE1E3EC,
        keyBackgroundColor = 0xffFFFFFF,
        keyTextColor = 0xff1B1B1F,
        accentKeyBackgroundColor = 0xff2563EB,
        accentKeyTextColor = 0xffFFFFFF,
        keyPressHighlightColor = 0x1A000000,
        popupBackgroundColor = 0xffFFFFFF,
        functionKeyBackgroundColor = 0xffC4C9D6,
        functionKeyPressHighlightColor = 0x1A000000,
    )

    val PixelDark = Theme.Builtin(
        name = "PixelDark",
        isDark = true,
        keyboardResources = 0x0,
        barColor = 0xff18191C,
        keyboardColor = 0xff1A1B1E,
        keyBackgroundColor = 0xff3E4047,
        keyTextColor = 0xffF4F4F6,
        accentKeyBackgroundColor = 0xff8AB4F8,
        accentKeyTextColor = 0xff0B1933,
        keyPressHighlightColor = 0x26FFFFFF,
        popupBackgroundColor = 0xff4B4E57,
        functionKeyBackgroundColor = 0xff282A2F,
        functionKeyPressHighlightColor = 0x22FFFFFF,
    )

    val AMOLEDBlack = Theme.Builtin(
        name = "AMOLEDBlack",
        isDark = true,
        keyboardResources = 0x0,
        barColor = 0xff050505,
        keyboardColor = 0xff000000,
        keyBackgroundColor = 0xff1F1F23,
        keyTextColor = 0xffFAFAFA,
        accentKeyBackgroundColor = 0xffEDEDED,
        accentKeyTextColor = 0xff09090B,
        keyPressHighlightColor = 0x2EFFFFFF,
        popupBackgroundColor = 0xff2A2A30,
        functionKeyBackgroundColor = 0xff121215,
        functionKeyPressHighlightColor = 0x24FFFFFF,
    )

    val NordLight = Theme.Builtin(
        name = "NordLight",
        isDark = false,
        keyboardResources = 0x0,
        barColor = 0xffE5E9F0,
        keyboardColor = 0xffD8DEE9,
        keyBackgroundColor = 0xffFFFFFF,
        keyTextColor = 0xff2E3440,
        accentKeyBackgroundColor = 0xff5E81AC,
        accentKeyTextColor = 0xffECEFF4,
        keyPressHighlightColor = 0x1A000000,
        popupBackgroundColor = 0xffFFFFFF,
        functionKeyBackgroundColor = 0xffC2CAD8,
        functionKeyPressHighlightColor = 0x1A000000,
    )

    val NordDark = Theme.Builtin(
        name = "NordDark",
        isDark = true,
        keyboardResources = 0x0,
        barColor = 0xff242933,
        keyboardColor = 0xff2E3440,
        keyBackgroundColor = 0xff434C5E,
        keyTextColor = 0xffECEFF4,
        accentKeyBackgroundColor = 0xff88C0D0,
        accentKeyTextColor = 0xff1D2430,
        keyPressHighlightColor = 0x26FFFFFF,
        popupBackgroundColor = 0xff4C566A,
        functionKeyBackgroundColor = 0xff363E4D,
        functionKeyPressHighlightColor = 0x22FFFFFF,
    )

    val Monokai = Theme.Builtin(
        name = "Monokai",
        isDark = true,
        keyboardResources = 0x0,
        barColor = 0xff191A16,
        keyboardColor = 0xff22231E,
        keyBackgroundColor = 0xff383A32,
        keyTextColor = 0xffF8F8F2,
        accentKeyBackgroundColor = 0xffF92672,
        accentKeyTextColor = 0xffFFFFFF,
        keyPressHighlightColor = 0x26FFFFFF,
        popupBackgroundColor = 0xff44463C,
        functionKeyBackgroundColor = 0xff2B2C26,
        functionKeyPressHighlightColor = 0x22FFFFFF,
    )

    /**
     * transparent background with semi-transparent white keys
     */
    val TransparentDark = Theme.Builtin(
        name = "TransparentDark",
        isDark = true,
        keyboardResources = 0x0,
        barColor = 0x4c000000,
        keyboardColor = 0x00000000,
        keyBackgroundColor = 0x4bffffff,
        keyTextColor = 0xffffffff,
        accentKeyBackgroundColor = 0xff5e97f6,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x1f000000,
        popupBackgroundColor = 0xff373737,
        functionKeyBackgroundColor = 0x4bffffff,
        functionKeyPressHighlightColor = 0x1fffffff,
    )

    /**
     * transparent background with semi-transparent black keys
     */
    val TransparentLight = Theme.Builtin(
        name = "TransparentLight",
        isDark = false,
        keyboardResources = 0x0,
        barColor = 0x26000000,
        keyboardColor = 0x00000000,
        keyBackgroundColor = 0x4bffffff,
        keyTextColor = 0xff000000,
        accentKeyBackgroundColor = 0xff5e97f6,
        accentKeyTextColor = 0xffffffff,
        keyPressHighlightColor = 0x1f000000,
        popupBackgroundColor = 0xffeeeeee,
        functionKeyBackgroundColor = 0x5affffff,
        functionKeyPressHighlightColor = 0x1f000000,
    )
}