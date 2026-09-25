package org.bitfennec.lime.entity

import org.bitfennec.lime.prefs.behavior.SkbMenuMode

class SkbFunItem(val funName: String, val funImgResource: Int, val skbMenuMode: SkbMenuMode){
    override fun equals(other: Any?): Boolean {
        return when(other) {
            !is SkbFunItem -> false
            else -> this === other || this.skbMenuMode == other.skbMenuMode
        }
    }
}
