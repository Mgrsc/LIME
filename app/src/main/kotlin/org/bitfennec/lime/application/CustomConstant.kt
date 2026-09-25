package org.bitfennec.lime.application

/**
 * Application global constants.
 */
object CustomConstant {
    val RIME_DICT_PATH: String
        get() = Launcher.instance.context.filesDir.resolve("rime").absolutePath


    const val SCHEMA_ZH_T9 = "t9_pinyin"
    const val SCHEMA_ZH_QWERTY = "pinyin"
    const val SCHEMA_EN = "english"
    const val SCHEMA_ZH_DOUBLE_FLYPY = "double_pinyin_flypy"
    const val LIME_IME_REPO = "https://github.com/Mgrsc/LIME"
    const val LICENSE_URL = "https://github.com/Mgrsc/LIME/blob/main/LICENSE"
}
