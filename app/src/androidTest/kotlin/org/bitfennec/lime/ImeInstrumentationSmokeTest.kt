package org.bitfennec.lime

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.bitfennec.lime.database.AppDatabase
import org.bitfennec.lime.service.ImeService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Baseline instrumentation smoke test for LIME IME.
 *
 * Verifies key platform integrations in an on-device/emulator environment:
 * 1. ImeService registration, exported flag, and BIND_INPUT_METHOD permission contract.
 * 2. Room SQLite database initialization and schema validation in sandbox environment.
 */
@RunWith(AndroidJUnit4::class)
class ImeInstrumentationSmokeTest {

    private lateinit var targetContext: Context

    @Before
    fun setUp() {
        targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @Test
    fun testImeServiceRegisteredWithProperPermissions() {
        val pm = targetContext.packageManager
        val component = ComponentName(targetContext.packageName, ImeService::class.java.name)

        val serviceInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getServiceInfo(component, PackageManager.ComponentInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getServiceInfo(component, PackageManager.GET_META_DATA)
        }

        assertNotNull("ImeService must be registered in AndroidManifest.xml", serviceInfo)
        assertTrue("ImeService must be exported for input method framework", serviceInfo.exported)
        assertEquals(
            "ImeService must require BIND_INPUT_METHOD permission",
            "android.permission.BIND_INPUT_METHOD",
            serviceInfo.permission
        )
    }

    @Test
    fun testSandboxRoomDatabaseInitialization() {
        val testDb = Room.inMemoryDatabaseBuilder(targetContext, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

        try {
            val dbVersion = testDb.openHelper.writableDatabase.version
            assertTrue("Database version must be >= 1", dbVersion >= 1)

            val cursor = testDb.openHelper.readableDatabase.query("SELECT name FROM sqlite_master WHERE type='table'")
            val tableNames = mutableSetOf<String>()
            cursor.use {
                while (it.moveToNext()) {
                    tableNames.add(it.getString(0))
                }
            }

            assertTrue("clipboard table must exist in Room schema", tableNames.contains("clipboard"))
            assertTrue("side_symbol table must exist in Room schema", tableNames.contains("side_symbol"))
            assertTrue("usedSymbol table must exist in Room schema", tableNames.contains("usedSymbol"))
            assertTrue("skbfun table must exist in Room schema", tableNames.contains("skbfun"))
        } finally {
            testDb.close()
        }
    }
}
