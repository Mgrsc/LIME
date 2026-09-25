package org.bitfennec.lime.utils

object QwertyPinYinUtils {
    fun getQwertyComposition(composition: String, comment: String): String {
        if(comment.isEmpty())return composition
        val compositionList = composition.filter { it.code <= 0xFF }.split("'".toRegex())
        val commentList = comment.split("'")
        return buildString {
            append(composition.filter { it.code > 0xFF })
            compositionList.forEachIndexed { index, compo ->
                val pinyin = commentList.getOrNull(index)
                if (pinyin == null) {
                    append(compo).append("'")
                    return@forEachIndexed
                }
                if (compo == pinyin) append(pinyin).append("'")
                else if (pinyin.startsWith(compo)) append(pinyin.substring(0, compo.length)).append("'")
                else {
                    val common = compo.zip(pinyin).takeWhile { (c, p) -> c == p }.joinToString("") { (c, _) -> c.toString() }
                    append(common).append("'")
                    append(compo.removePrefix(common))
                }
            }
        }
    }
}
