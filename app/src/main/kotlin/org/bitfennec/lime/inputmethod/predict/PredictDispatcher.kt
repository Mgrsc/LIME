package org.bitfennec.lime.inputmethod.predict

import kotlinx.coroutines.CoroutineDispatcher
import org.bitfennec.lime.inputmethod.ImeDispatchers

/**
 * Dedicated background single-thread dispatcher for next-word prediction queries and updates.
 * Decoupled from UI thread, key actor queue, and Rime dispatcher; reuses ImeDispatchers.idleDispatcher.
 */
object PredictDispatcher {
    val dispatcher: CoroutineDispatcher
        get() = ImeDispatchers.idleDispatcher
}
