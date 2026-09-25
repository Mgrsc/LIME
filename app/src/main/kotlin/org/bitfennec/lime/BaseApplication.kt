package org.bitfennec.lime

import android.app.Application
import android.content.ComponentCallbacks2
import org.bitfennec.lime.core.HandwritingEngine
import org.bitfennec.lime.application.Launcher
import org.bitfennec.lime.inputmethod.voice.VoiceRecognitionEngine
import org.bitfennec.lime.utils.KeyboardLoaderUtil
import org.bitfennec.lime.utils.CrashHandler

/**
 * Application entry point.
 * @since 2019/6/18
 */
class BaseApplication : Application(){
    override fun onCreate() {
        super.onCreate()
        CrashHandler.init(this)
        Launcher.instance.initData(baseContext)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_UI_HIDDEN) {
            KeyboardLoaderUtil.instance.clearKeyboardMap()
        }
        if (level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) {
            VoiceRecognitionEngine.release()
            HandwritingEngine.release()
        }
    }
}
