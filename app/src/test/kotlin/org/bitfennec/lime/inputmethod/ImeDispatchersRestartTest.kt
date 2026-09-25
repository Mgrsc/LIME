package org.bitfennec.lime.inputmethod

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Test

class ImeDispatchersRestartTest {

    @Test
    fun testDispatcherRebuildAfterShutdown() = runBlocking {
        // 1. Initial task dispatch across hw, asr, idle
        val hwResult1 = withContext(ImeDispatchers.hwDispatcher) { "hw_task_1" }
        assertEquals("hw_task_1", hwResult1)

        val asrResult1 = withContext(ImeDispatchers.asrDispatcher) { "asr_task_1" }
        assertEquals("asr_task_1", asrResult1)

        val idleResult1 = withContext(ImeDispatchers.idleDispatcher) { "idle_task_1" }
        assertEquals("idle_task_1", idleResult1)

        // 2. Simulate IME shutdown
        ImeDispatchers.shutdown()

        // 3. Simulate task dispatch after restart to verify recreation without RejectedExecutionException
        val hwResult2 = withContext(ImeDispatchers.hwDispatcher) { "hw_task_after_restart" }
        assertEquals("hw_task_after_restart", hwResult2)

        val asrResult2 = withContext(ImeDispatchers.asrDispatcher) { "asr_task_after_restart" }
        assertEquals("asr_task_after_restart", asrResult2)

        val idleResult2 = withContext(ImeDispatchers.idleDispatcher) { "idle_task_after_restart" }
        assertEquals("idle_task_after_restart", idleResult2)
    }
}
