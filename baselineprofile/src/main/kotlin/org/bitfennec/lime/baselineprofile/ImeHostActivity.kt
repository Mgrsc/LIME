package org.bitfennec.lime.baselineprofile

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.EditText

class ImeHostActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ime_host)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_RESTART_INPUT, false)) {
            val field = findViewById<EditText>(R.id.host_field)
            getSystemService(InputMethodManager::class.java).restartInput(field)
        }
    }

    private companion object {
        const val EXTRA_RESTART_INPUT = "restart_input"
    }
}
