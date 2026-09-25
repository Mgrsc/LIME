package org.bitfennec.lime.baselineprofile

import android.app.Activity
import android.os.Bundle

class ImeHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ime_host)
    }
}
