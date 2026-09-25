package org.bitfennec.lime.keyboard

import android.view.KeyEvent
import org.bitfennec.lime.manager.InputModeSwitcher

object KeyboardData {
    val layoutQwertyCn: ArrayList<Array<Int>> = arrayListOf(
        arrayOf(45, 51, 33, 46, 48, 53, 49, 37, 43, 44),
        arrayOf(29, 47, 32, 34, 35, 36, 38, 39, 40),
        arrayOf(54, 52, 31, 50, 30, 42, 41, KeyEvent.KEYCODE_DEL),
    )

    val layoutQwerty9Cn: ArrayList<Array<Int>> = arrayListOf(
        arrayOf(51, 33, 46, 48, 53, 49, 37, 43, 44),
        arrayOf(29, 47, 32, 34, 35, 36, 38, 39, 40),
        arrayOf(45, 54, 52, 31, 50, 30, 42, 41, KeyEvent.KEYCODE_DEL),
    )

    val layoutT9Cn: ArrayList<Array<Int>> = arrayListOf(
        arrayOf(InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL, KeyEvent.KEYCODE_APOSTROPHE, KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_D, KeyEvent.KEYCODE_DEL),
        arrayOf(KeyEvent.KEYCODE_G, KeyEvent.KEYCODE_J, KeyEvent.KEYCODE_M, KeyEvent.KEYCODE_CLEAR),
        arrayOf(KeyEvent.KEYCODE_P, KeyEvent.KEYCODE_T, KeyEvent.KEYCODE_W, KeyEvent.KEYCODE_AT),
    )

    val layoutHandwritingCn: ArrayList<Array<Int>> = arrayListOf(
        arrayOf(InputModeSwitcher.USER_KEYCODE_NUMBER, InputModeSwitcher.USER_KEYCODE_SYMBOL, KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_DEL, KeyEvent.KEYCODE_ENTER),
    )

    val layoutQwertyEn: ArrayList<Array<Int>> = arrayListOf(
        arrayOf(45, 51, 33, 46, 48, 53, 49, 37, 43, 44),
        arrayOf(29, 47, 32, 34, 35, 36, 38, 39, 40),
        arrayOf(54, 52, 31, 50, 30, 42, 41, KeyEvent.KEYCODE_DEL),
    )

    val layoutQwerty9En: ArrayList<Array<Int>> = ArrayList(layoutQwerty9Cn)

    val layoutT9Number: ArrayList<Array<Int>> = arrayListOf(
        arrayOf(InputModeSwitcher.USER_KEYCODE_LEFT_SYMBOL, 8, 9, 10, KeyEvent.KEYCODE_DEL),
        arrayOf(11, 12, 13, InputModeSwitcher.USER_KEYCODE_LEFT_PERIOD),
        arrayOf(14, 15, 16, KeyEvent.KEYCODE_AT),
    )
}
